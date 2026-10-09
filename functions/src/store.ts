import { Firestore } from "firebase-admin/firestore";
import { randomUUID } from "node:crypto";
import {
  OFFLINE_WINDOW_MS, PurchaseRecord, PurchaseStatus, PurchaseStore, VerificationError,
  tokenHash,
} from "./verification";

export class FirestorePurchaseStore implements PurchaseStore {
  constructor(private readonly db: Firestore) {}

  async assertAccountActive(uid: string): Promise<void> {
    if (!uid || (await this.db.collection("proAccountDeletions").doc(tokenHash(uid)).get()).exists) {
      throw new VerificationError("failed-precondition", "This account is being deleted. Pro access cannot be restored.");
    }
  }

  async acquire(hash: string): Promise<string> {
    const lease = randomUUID();
    const ref = this.db.collection("proPurchaseLocks").doc(hash);
    await this.db.runTransaction(async (transaction) => {
      const snapshot = await transaction.get(ref);
      if (snapshot.exists && snapshot.get("expiresAt") > Date.now()) {
        throw new VerificationError("failed-precondition", "This purchase is being verified. Retry shortly.");
      }
      transaction.set(ref, { lease, expiresAt: Date.now() + 90000 });
    });
    return lease;
  }

  async release(hash: string, lease: string): Promise<void> {
    const ref = this.db.collection("proPurchaseLocks").doc(hash);
    await this.db.runTransaction(async (transaction) => {
      const snapshot = await transaction.get(ref);
      if (snapshot.get("lease") === lease) transaction.delete(ref);
    });
  }

  async get(hash: string): Promise<PurchaseRecord | null> {
    const snapshot = await this.db.collection("proPurchases").doc(hash).get();
    return snapshot.exists ? this.decode(snapshot.data()) : null;
  }

  async claim(hash: string, uid: string, token: string, lease: string, googleAccountId: string): Promise<void> {
    const ref = this.db.collection("proPurchases").doc(hash);
    await this.db.runTransaction(async (transaction) => {
      await this.requireLease(transaction, hash, lease);
      await this.requireAccountActive(transaction, uid);
      const snapshot = await transaction.get(ref);
      if (snapshot.exists) {
        const record = this.decode(snapshot.data());
        if (record.googleAccountId !== googleAccountId || (record.uid !== uid && record.uid !== "")) {
          throw new VerificationError("permission-denied", "This purchase is already linked to another account.");
        }
        if (record.uid === "") transaction.update(ref, { uid });
        return;
      }
      transaction.create(ref, {
        uid, googleAccountId, token, status: "verifying", verifiedAt: null, expiresAt: null,
      } satisfies PurchaseRecord);
    });
  }

  async update(hash: string, uid: string, status: PurchaseStatus, now: number, lease: string): Promise<void> {
    const ref = this.db.collection("proPurchases").doc(hash);
    await this.db.runTransaction(async (transaction) => {
      await this.requireLease(transaction, hash, lease);
      await this.requireAccountActive(transaction, uid);
      const snapshot = await transaction.get(ref);
      if (!snapshot.exists || this.decode(snapshot.data()).uid !== uid) {
        throw new VerificationError("permission-denied", "Purchase ownership could not be confirmed.");
      }
      transaction.update(ref, {
        status,
        verifiedAt: status === "active" ? now : null,
        expiresAt: status === "active" ? now + OFFLINE_WINDOW_MS : null,
      });
    });
  }

  async forUser(uid: string): Promise<PurchaseRecord[]> {
    if (!uid || (await this.db.collection("proAccountDeletions").doc(tokenHash(uid)).get()).exists) return [];
    const snapshots = await this.db.collection("proPurchases").where("uid", "==", uid).get();
    return snapshots.docs.map((snapshot) => this.decode(snapshot.data()));
  }

  async removeDetached(hash: string, lease: string): Promise<void> {
    const ref = this.db.collection("proPurchases").doc(hash);
    await this.db.runTransaction(async (transaction) => {
      await this.requireLease(transaction, hash, lease);
      const snapshot = await transaction.get(ref);
      if (snapshot.exists && snapshot.get("uid") === "") transaction.delete(ref);
    });
  }

  private async requireAccountActive(transaction: FirebaseFirestore.Transaction, uid: string) {
    const deletion = await transaction.get(this.db.collection("proAccountDeletions").doc(tokenHash(uid)));
    if (!uid || deletion.exists) {
      throw new VerificationError("failed-precondition", "This account is being deleted. Pro access cannot be restored.");
    }
  }

  private async requireLease(transaction: FirebaseFirestore.Transaction, hash: string, lease: string) {
    const snapshot = await transaction.get(this.db.collection("proPurchaseLocks").doc(hash));
    if (snapshot.get("lease") !== lease || snapshot.get("expiresAt") <= Date.now()) {
      throw new VerificationError("failed-precondition", "The verification lease expired. Retry shortly.");
    }
  }

  private decode(data: FirebaseFirestore.DocumentData | undefined): PurchaseRecord {
    if (!data || typeof data.uid !== "string" || typeof data.googleAccountId !== "string" ||
        !/^[a-f0-9]{64}$/.test(data.googleAccountId) || typeof data.token !== "string" ||
        !["verifying", "active", "pending", "revoked"].includes(data.status) ||
        (data.verifiedAt !== null && !Number.isFinite(data.verifiedAt)) ||
        (data.expiresAt !== null && !Number.isFinite(data.expiresAt))) {
      throw new Error("Stored purchase record is invalid.");
    }
    return {
      uid: data.uid, googleAccountId: data.googleAccountId, token: data.token, status: data.status,
      verifiedAt: data.verifiedAt, expiresAt: data.expiresAt,
    };
  }
}
