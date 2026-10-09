import { Firestore } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { tokenHash } from "./verification";
import { logger } from "firebase-functions";

export async function deleteFirebaseAuthUser(uid: string): Promise<void> {
  try {
    await getAuth().deleteUser(uid);
  } catch (error) {
    if (typeof error === "object" && error !== null && "code" in error &&
        error.code === "auth/user-not-found") return;
    throw error;
  }
}

export class AccountDeletion {
  constructor(
    private readonly db: Firestore,
    private readonly deleteAuthUser: (uid: string) => Promise<void>,
  ) {}

  async start(uid: string): Promise<void> {
    const ref = this.db.collection("proAccountDeletions").doc(tokenHash(uid));
    await this.db.runTransaction(async (transaction) => {
      const current = await transaction.get(ref);
      if (!current.exists) transaction.create(ref, { uid, status: "pending", createdAt: Date.now() });
    });
    await this.finish(uid);
  }

  async finish(uid: string): Promise<void> {
    // Keep the deletion fence until all old authentication tokens/requests have expired.
    await this.deleteAuthUser(uid);
    const collection = this.db.collection("proPurchases");
    while (true) {
      const page = await collection.where("uid", "==", uid).limit(100).get();
      if (page.empty) break;
      await this.db.runTransaction(async (transaction) => {
        const snapshots = await transaction.getAll(...page.docs.map((doc) => doc.ref));
        for (const snapshot of snapshots) {
          if (snapshot.get("uid") !== uid) continue;
          if (snapshot.get("status") === "revoked") transaction.delete(snapshot.ref);
          else transaction.update(snapshot.ref, {
            uid: "", status: "verifying", verifiedAt: null, expiresAt: null,
          });
        }
      });
    }
    await this.db.collection("proAccountDeletions").doc(tokenHash(uid)).set({
      status: "complete", completedAt: Date.now(),
    });
  }

  async resumePending(): Promise<boolean> {
    let succeeded = true;
    const pending = await this.db.collection("proAccountDeletions").where("status", "==", "pending").get();
    for (const document of pending.docs) {
      try {
        const uid: unknown = document.get("uid");
        if (typeof uid !== "string" || !uid) throw new Error("Invalid pending account deletion.");
        await this.finish(uid);
      } catch (error) {
        succeeded = false;
        logger.error("Scheduled account deletion failed; a later run will retry.", {
          deletionHash: document.id, errorType: error instanceof Error ? error.name : "unknown",
        });
      }
    }
    const completed = await this.db.collection("proAccountDeletions").where("status", "==", "complete").get();
    for (const document of completed.docs) {
      if (document.get("completedAt") < Date.now() - 24 * 60 * 60 * 1000) {
        await document.ref.delete();
      }
    }
    return succeeded;
  }
}
