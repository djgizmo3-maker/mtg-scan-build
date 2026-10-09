import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { AccountDeletion, deleteFirebaseAuthUser } from "./deletion";
import { googlePurchaseIdentity, requireRecentGoogleAuth } from "./identity";
import { logger } from "firebase-functions";
import { setGlobalOptions } from "firebase-functions/v2";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onMessagePublished } from "firebase-functions/v2/pubsub";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { AndroidPublisher } from "./play";
import { FirestorePurchaseStore } from "./store";
import {
  playNotification, purchaseToken, PurchaseVerifier, tokenHash, VerificationError,
} from "./verification";

initializeApp();
setGlobalOptions({
  region: "us-central1",
  serviceAccount: "mtg-pro-verifier@mtg-scan-build-7b54f.iam.gserviceaccount.com",
  maxInstances: 2,
  concurrency: 20,
  timeoutSeconds: 120,
});
const db = getFirestore();
const store = new FirestorePurchaseStore(db);
const verifier = new PurchaseVerifier(new AndroidPublisher(), store);
const deletion = new AccountDeletion(db, deleteFirebaseAuthUser);

function uid(auth: { uid: string } | undefined): string {
  if (!auth) throw new HttpsError("unauthenticated", "Sign in before managing Pro.");
  return auth.uid;
}

function failure(error: unknown, operation: string): never {
  if (error instanceof HttpsError) throw error;
  if (error instanceof VerificationError) throw new HttpsError(error.code, error.message);
  // Google API exceptions can contain purchase tokens and authorization headers.
  logger.error("Pro backend operation failed.", {
    operation, errorType: error instanceof Error ? error.name : "unknown",
  });
  throw new HttpsError("unavailable", "Purchase verification is temporarily unavailable. Retry shortly.");
}

export const proPurchaseAccount = onCall<unknown>({ enforceAppCheck: true }, async (request) => {
  try {
    const binding = googlePurchaseIdentity(request.auth);
    await store.assertAccountActive(uid(request.auth));
    return { obfuscatedAccountId: binding };
  } catch (error) {
    return failure(error, "purchase-account");
  }
});

export const verifyProPurchase = onCall<unknown>({ enforceAppCheck: true }, async (request) => {
  try {
    const user = uid(request.auth);
    const token = purchaseToken(request.data);
    const binding = googlePurchaseIdentity(request.auth);
    const purchaseStatus = await verifier.verify(user, token, binding);
    return { purchaseStatus, ...await verifier.entitlement(user, binding) };
  } catch (error) {
    return failure(error, "verify");
  }
});

export const refreshProEntitlement = onCall<unknown>({ enforceAppCheck: true }, async (request) => {
  try {
    return await verifier.refresh(uid(request.auth), googlePurchaseIdentity(request.auth));
  } catch (error) {
    return failure(error, "refresh");
  }
});

export const deleteProAccount = onCall<unknown>({ enforceAppCheck: true }, async (request) => {
  try {
    requireRecentGoogleAuth(request.auth);
    await deletion.start(uid(request.auth));
    return { deleted: true };
  } catch (error) {
    return failure(error, "delete-account");
  }
});

export const reconcileProPurchases = onSchedule({
  schedule: "every 60 minutes",
  timeZone: "Etc/UTC",
}, async () => {
  let failed = !await deletion.resumePending();
  // Cursor pagination bounds memory while retaining incomplete acknowledgements for recovery.
  let cursor: FirebaseFirestore.QueryDocumentSnapshot | undefined;
  do {
    let query = db.collection("proPurchases").orderBy("__name__").limit(100);
    if (cursor) query = query.startAfter(cursor);
    const page = await query.get();
    for (const snapshot of page.docs) {
      try {
        const record = await store.get(snapshot.id);
        if (record && record.uid && record.status !== "revoked") {
          await verifier.verify(record.uid, record.token, record.googleAccountId);
        }
        if (record?.uid === "") await verifier.reconcileDetached(record.token);
      } catch (error) {
        failed = true;
        logger.error("Scheduled purchase verification failed.", {
          purchaseHash: snapshot.id, errorType: error instanceof Error ? error.name : "unknown",
        });
      }
    }
    cursor = page.docs.length === 100 ? page.docs[page.docs.length - 1] : undefined;
  } while (cursor);
  if (failed) throw new Error("One or more purchases could not be reconciled.");
});

export const playPurchaseNotification = onMessagePublished({
  topic: "mtg-play-purchases",
  retry: true,
}, async (event) => {
  const notification = playNotification(event.data.message.json);
  if (notification.kind === "ignored") {
    logger.warn("Ignored notification for an unsupported product type or package.");
    return;
  }
  if (notification.kind === "test") {
    logger.info("Google Play test notification received.");
    return;
  }
  const token = notification.token;
  const record = await store.get(tokenHash(token));
  if (!record) {
    logger.info("Notification received before purchase registration.");
    return;
  }
  if (!record.uid) {
    await verifier.reconcileDetached(token);
    return;
  }
  try {
    // Never grant or revoke from the notification payload alone: re-query Google Play.
    await verifier.verify(record.uid, token, record.googleAccountId);
  } catch (error) {
    logger.error("Play notification verification failed.", {
      errorType: error instanceof Error ? error.name : "unknown",
    });
    throw new Error("Play notification could not be verified.");
  }
});
