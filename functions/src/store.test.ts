import { strict as assert } from "node:assert";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { FirestorePurchaseStore } from "./store";
import { accountId, OFFLINE_WINDOW_MS, tokenHash } from "./verification";
import { AccountDeletion, deleteFirebaseAuthUser } from "./deletion";

if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST ||
    process.env.GCLOUD_PROJECT !== "demo-mtg-pro") {
  throw new Error("Integration tests require local Firestore and Auth emulators for demo-mtg-pro.");
}

initializeApp({ projectId: "demo-mtg-pro" });
const db = getFirestore();
const store = new FirestorePurchaseStore(db);

test("Firestore transaction enforces exclusive purchase verification", async () => {
  const hash = tokenHash(randomUUID());
  const claims = await Promise.allSettled([store.acquire(hash), store.acquire(hash)]);
  const winner = claims.find((result) => result.status === "fulfilled");
  assert.ok(winner?.status === "fulfilled");
  assert.equal(claims.filter((result) => result.status === "rejected").length, 1);
  await store.release(hash, winner.value);
  const next = await store.acquire(hash);
  await store.release(hash, next);
});

test("Firestore prevents changing token ownership and stores exact server expiry", async () => {
  const token = randomUUID();
  const hash = tokenHash(token);
  const lease = await store.acquire(hash);
  try {
    await store.claim(hash, "owner", token, lease, accountId("owner"));
    await assert.rejects(store.claim(hash, "another", token, lease, accountId("another")), { code: "permission-denied" });
    const now = Date.UTC(2026, 9, 8);
    await store.update(hash, "owner", "active", now, lease);
    assert.equal((await store.get(hash))?.expiresAt, now + OFFLINE_WINDOW_MS);
    await assert.rejects(store.update(hash, "another", "active", now, lease), { code: "permission-denied" });
    await store.update(hash, "owner", "revoked", now + 1, lease);
    assert.equal((await store.get(hash))?.expiresAt, null);
    assert.equal((await store.get(hash))?.status, "revoked");
  } finally {
    await store.release(hash, lease);
  }
});

test("expired verification cannot overwrite a newer worker's decision", async () => {
  const token = randomUUID();
  const hash = tokenHash(token);
  const oldLease = await store.acquire(hash);
  await store.claim(hash, "owner", token, oldLease, accountId("owner"));
  await db.collection("proPurchaseLocks").doc(hash).update({ expiresAt: 0 });
  const newLease = await store.acquire(hash);
  try {
    await store.update(hash, "owner", "revoked", Date.now(), newLease);
    await assert.rejects(store.update(hash, "owner", "active", Date.now(), oldLease), {
      code: "failed-precondition",
    });
    await store.release(hash, oldLease);
    await assert.rejects(store.acquire(hash), { code: "failed-precondition" });
    assert.equal((await store.get(hash))?.status, "revoked");
  } finally {
    await store.release(hash, newLease);
  }
});

test("client rules deny reads and writes to purchases and verification locks", async () => {
  const base = `http://${process.env.FIRESTORE_EMULATOR_HOST}/v1/projects/demo-mtg-pro/databases/(default)/documents`;
  for (const collection of ["proPurchases", "proPurchaseLocks", "proAccountDeletions", "unrelatedCollection"]) {
    const document = `${base}/${collection}/client-attempt`;
    const read = await fetch(document);
    assert.equal(read.status, 403);
    const write = await fetch(document, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fields: { hasPro: { booleanValue: true } } }),
    });
    assert.equal(write.status, 403);
  }
});

test("deletion fences stale requests, removes Auth, and detaches only minimal recovery data", async () => {
  const uid = randomUUID();
  const binding = accountId("google-" + uid);
  const token = randomUUID();
  const hash = tokenHash(token);
  const lease = await store.acquire(hash);
  await store.claim(hash, uid, token, lease, binding);
  await store.update(hash, uid, "active", Date.now(), lease);
  let removedUser = "";
  const deletion = new AccountDeletion(db, async (user) => { removedUser = user; });
  await deletion.start(uid);
  assert.equal(removedUser, uid);
  await assert.rejects(store.assertAccountActive(uid), { code: "failed-precondition" });
  assert.equal((await store.forUser(uid)).length, 0);
  await assert.rejects(store.update(hash, uid, "active", Date.now(), lease), { code: "failed-precondition" });
  await assert.rejects(store.claim(hash, uid, token, lease, binding), { code: "failed-precondition" });
  const detached = await store.get(hash);
  assert.equal(detached?.uid, "");
  assert.equal(detached?.googleAccountId, binding);
  assert.equal(detached?.expiresAt, null);
  await assert.rejects(store.claim(hash, "attacker-" + uid, token, lease, accountId("attacker")), {
    code: "permission-denied",
  });
  await store.claim(hash, "replacement-" + uid, token, lease, binding);
  await store.update(hash, "replacement-" + uid, "active", Date.now(), lease);
  assert.equal((await store.get(hash))?.uid, "replacement-" + uid);
  await store.release(hash, lease);
});

test("failed Auth deletion leaves a recoverable fence and scheduled retry completes cleanup", async () => {
  const uid = randomUUID();
  let fail = true;
  const deletion = new AccountDeletion(db, async () => {
    if (fail) throw new Error("Auth unavailable");
  });
  await assert.rejects(deletion.start(uid), /Auth unavailable/);
  assert.equal((await db.collection("proAccountDeletions").doc(tokenHash(uid)).get()).get("status"), "pending");
  fail = false;
  assert.equal(await deletion.resumePending(), true);
  const completed = await db.collection("proAccountDeletions").doc(tokenHash(uid)).get();
  assert.equal(completed.get("status"), "complete");
  assert.equal(completed.get("uid"), undefined);
});

test("deletion removes revoked records and expires completed markers after 24 hours", async () => {
  const uid = randomUUID();
  const token = randomUUID();
  const hash = tokenHash(token);
  const lease = await store.acquire(hash);
  await store.claim(hash, uid, token, lease, accountId(uid));
  await store.update(hash, uid, "revoked", Date.now(), lease);
  await store.release(hash, lease);
  const deletion = new AccountDeletion(db, async () => {});
  await deletion.start(uid);
  assert.equal(await store.get(hash), null);
  const marker = db.collection("proAccountDeletions").doc(tokenHash(uid));
  await marker.update({ completedAt: Date.now() - 24 * 60 * 60 * 1000 - 1 });
  await deletion.resumePending();
  assert.equal((await marker.get()).exists, false);
});

test("real emulator Auth profile is deleted and retrying deletion is idempotent", async () => {
  const uid = randomUUID();
  await getAuth().createUser({
    uid, email: `${uid}@example.test`, emailVerified: true, displayName: "Deletion test",
  });
  const deletion = new AccountDeletion(db, deleteFirebaseAuthUser);
  await deletion.start(uid);
  await assert.rejects(getAuth().getUser(uid), { code: "auth/user-not-found" });
  await deletion.start(uid);
  const marker = await db.collection("proAccountDeletions").doc(tokenHash(uid)).get();
  assert.equal(marker.get("status"), "complete");
  assert.equal(marker.get("uid"), undefined);
});

test("a failed deletion does not prevent cleanup of a different account", async () => {
  const failedUid = randomUUID();
  const otherUid = randomUUID();
  let fail = true;
  const deletion = new AccountDeletion(db, async (uid) => {
    if (fail && uid === failedUid) throw new Error("Auth temporarily unavailable");
  });
  await assert.rejects(deletion.start(failedUid), /temporarily unavailable/);
  await db.collection("proAccountDeletions").doc(tokenHash(otherUid)).set({
    uid: otherUid, status: "pending", createdAt: Date.now(),
  });
  assert.equal(await deletion.resumePending(), false);
  assert.equal((await db.collection("proAccountDeletions").doc(tokenHash(otherUid)).get()).get("status"), "complete");
  fail = false;
  assert.equal(await deletion.resumePending(), true);
});
