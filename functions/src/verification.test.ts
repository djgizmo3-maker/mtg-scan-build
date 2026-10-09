import { strict as assert } from "node:assert";
import { test } from "node:test";
import {
  accountId, OFFLINE_WINDOW_MS, PACKAGE_NAME, PlayApi, playNotification, PlayPurchase, PRODUCT_ID,
  purchaseToken, PurchaseRecord, PurchaseStatus, PurchaseStore, PurchaseVerifier,
  tokenHash, VerificationError,
} from "./verification";

const UID = "firebase-user-one";
const TOKEN = "play-purchase-token";
const NOW = Date.UTC(2026, 9, 8);

class MemoryStore implements PurchaseStore {
  records = new Map<string, PurchaseRecord>();
  leases = new Map<string, string>();
  async acquire(hash: string) {
    if (this.leases.has(hash)) throw new VerificationError("failed-precondition", "Busy.");
    const lease = "lease";
    this.leases.set(hash, lease);
    return lease;
  }
  async release(hash: string, lease: string) {
    if (this.leases.get(hash) === lease) this.leases.delete(hash);
  }
  async get(hash: string) { return this.records.get(hash) ?? null; }
  async claim(hash: string, uid: string, token: string, lease: string, googleAccountId = accountId(uid)) {
    assert.equal(this.leases.get(hash), lease);
    const current = this.records.get(hash);
    if (current && (current.googleAccountId !== googleAccountId ||
        (current.uid !== uid && current.uid !== ""))) throw new VerificationError("permission-denied", "Wrong owner.");
    if (current) this.records.set(hash, { ...current, uid });
    else this.records.set(hash, { uid, googleAccountId, token, status: "verifying", verifiedAt: null, expiresAt: null });
  }
  async update(hash: string, uid: string, status: PurchaseStatus, now: number, lease: string) {
    assert.equal(this.leases.get(hash), lease);
    const current = this.records.get(hash);
    assert.ok(current);
    assert.equal(current.uid, uid);
    this.records.set(hash, {
      ...current, status,
      verifiedAt: status === "active" ? now : null,
      expiresAt: status === "active" ? now + OFFLINE_WINDOW_MS : null,
    });
  }
  async forUser(uid: string) { return [...this.records.values()].filter((record) => record.uid === uid); }
  async removeDetached(hash: string, lease: string) {
    assert.equal(this.leases.get(hash), lease);
    if (this.records.get(hash)?.uid === "") this.records.delete(hash);
  }
}

class TestVerifier extends PurchaseVerifier {
  override verify(uid: string, token: string, binding = accountId(uid)) {
    return super.verify(uid, token, binding);
  }
  override refresh(uid: string, binding = accountId(uid)) {
    return super.refresh(uid, binding);
  }
}

function fixture() {
  const store = new MemoryStore();
  let now = NOW;
  let acknowledgements = 0;
  let purchase: PlayPurchase = {
    purchaseState: 0, consumptionState: 0, acknowledgementState: 0,
    productId: PRODUCT_ID, obfuscatedExternalAccountId: accountId(UID), quantity: 1,
  };
  const play: PlayApi = {
    async get() { return { ...purchase }; },
    async acknowledge() { acknowledgements++; purchase.acknowledgementState = 1; },
  };
  const verifier = new TestVerifier(play, store, () => now);
  return {
    store, play, verifier,
    purchase: () => purchase,
    setPurchase: (value: PlayPurchase) => { purchase = value; },
    setNow: (value: number) => { now = value; },
    acknowledgements: () => acknowledgements,
  };
}

test("rejects missing, malformed and oversized purchase tokens", () => {
  for (const data of [null, {}, [], { purchaseToken: 1 }, { purchaseToken: "" },
    { purchaseToken: " token" }, { purchaseToken: "token\n" }, { purchaseToken: "a".repeat(4097) }]) {
    assert.throws(() => purchaseToken(data), VerificationError);
  }
  assert.equal(purchaseToken({ purchaseToken: TOKEN }), TOKEN);
});

test("account binding uses a domain-separated SHA-256 Google subject", () => {
  assert.match(accountId(UID), /^[a-f0-9]{64}$/);
  assert.notEqual(accountId(UID), accountId("another-user"));
  assert.notEqual(accountId(UID), tokenHash(UID));
});

test("acknowledges once and grants exactly 30 days after server verification", async () => {
  const f = fixture();
  assert.equal(await f.verifier.verify(UID, TOKEN), "active");
  assert.equal(f.acknowledgements(), 1);
  assert.deepEqual(await f.verifier.entitlement(UID), {
    hasPro: true, verifiedAt: NOW, expiresAt: NOW + OFFLINE_WINDOW_MS, serverTime: NOW,
  });
  await f.verifier.verify(UID, TOKEN);
  assert.equal(f.acknowledgements(), 1);
});

test("entitlement expires at the precise 30-day boundary", async () => {
  const f = fixture();
  await f.verifier.verify(UID, TOKEN);
  f.setNow(NOW + OFFLINE_WINDOW_MS - 1);
  assert.equal((await f.verifier.entitlement(UID)).hasPro, true);
  f.setNow(NOW + OFFLINE_WINDOW_MS);
  assert.equal((await f.verifier.entitlement(UID)).hasPro, false);
});

test("successful refresh issues a new lease; failed refresh never extends it", async () => {
  const f = fixture();
  await f.verifier.verify(UID, TOKEN);
  f.setNow(NOW + 1000);
  await f.verifier.refresh(UID);
  const before = await f.verifier.entitlement(UID);
  assert.equal(before.expiresAt, NOW + 1000 + OFFLINE_WINDOW_MS);
  f.play.get = async () => { throw new Error("network failure"); };
  f.setNow(NOW + 2000);
  assert.equal((await f.verifier.refresh(UID)).verificationIncomplete, true);
  assert.equal((await f.verifier.entitlement(UID)).expiresAt, before.expiresAt);
  assert.equal(f.store.leases.size, 0);
});

test("pending and canceled new purchases never grant or acknowledge", async () => {
  for (const purchaseState of [1, 2]) {
    const f = fixture();
    f.setPurchase({ purchaseState });
    assert.equal(await f.verifier.verify(UID, TOKEN), purchaseState === 1 ? "revoked" : "pending");
    assert.equal((await f.verifier.entitlement(UID)).hasPro, false);
    assert.equal(f.acknowledgements(), 0);
    assert.equal(f.store.records.size, 0);
  }
});

test("refund removes an existing entitlement immediately even if response omits binding", async () => {
  const f = fixture();
  await f.verifier.verify(UID, TOKEN);
  f.setPurchase({ purchaseState: 1 });
  assert.equal(await f.verifier.verify(UID, TOKEN), "revoked");
  assert.equal((await f.verifier.entitlement(UID)).hasPro, false);
});

test("missing and mismatched account binding are rejected", async () => {
  for (const binding of [undefined, accountId("different-user")]) {
    const f = fixture();
    f.purchase().obfuscatedExternalAccountId = binding;
    await assert.rejects(f.verifier.verify(UID, TOKEN), { code: "permission-denied" });
    assert.equal(f.store.records.size, 0);
    assert.equal(f.acknowledgements(), 0);
  }
});

test("same token cannot be restored to another Firebase account", async () => {
  const f = fixture();
  await f.verifier.verify(UID, TOKEN);
  await assert.rejects(f.verifier.verify("different-user", TOKEN), { code: "permission-denied" });
  assert.equal((await f.verifier.entitlement("different-user")).hasPro, false);
  assert.equal((await f.verifier.entitlement(UID)).hasPro, true);
});

test("wrong product, consumed, multi-quantity and unknown states fail closed", async () => {
  for (const fields of [
    { productId: "other-product" }, { consumptionState: 1 }, { quantity: 2 },
    { quantity: 0 }, { acknowledgementState: 7 }, { purchaseState: 8 },
    { purchaseState: undefined }, { consumptionState: undefined },
  ]) {
    const f = fixture();
    Object.assign(f.purchase(), fields);
    await assert.rejects(f.verifier.verify(UID, TOKEN), { code: "failed-precondition" });
    assert.equal((await f.verifier.entitlement(UID)).hasPro, false);
  }
});

test("optional Play product ID and quantity fields may be omitted", async () => {
  const f = fixture();
  delete f.purchase().productId;
  delete f.purchase().quantity;
  assert.equal(await f.verifier.verify(UID, TOKEN), "active");
});

test("acknowledgement failure retains a recoverable record without unlocking", async () => {
  const f = fixture();
  const acknowledge = f.play.acknowledge;
  f.play.acknowledge = async () => { throw new Error("ack unavailable"); };
  await assert.rejects(f.verifier.verify(UID, TOKEN), /ack unavailable/);
  assert.equal(f.store.records.get(tokenHash(TOKEN))?.status, "verifying");
  assert.equal((await f.verifier.entitlement(UID)).hasPro, false);
  f.play.acknowledge = acknowledge;
  await f.verifier.refresh(UID);
  assert.equal((await f.verifier.entitlement(UID)).hasPro, true);
});

test("unconfirmed acknowledgement cannot grant access", async () => {
  const f = fixture();
  f.play.acknowledge = async () => {};
  await assert.rejects(f.verifier.verify(UID, TOKEN), { code: "failed-precondition" });
  assert.equal((await f.verifier.entitlement(UID)).hasPro, false);
});

test("cancellation during acknowledgement cannot grant access", async () => {
  const f = fixture();
  f.play.acknowledge = async () => { f.setPurchase({ purchaseState: 1 }); };
  assert.equal(await f.verifier.verify(UID, TOKEN), "revoked");
  assert.equal((await f.verifier.entitlement(UID)).hasPro, false);
});

test("a second in-flight verification cannot race acknowledgement or revocation", async () => {
  const f = fixture();
  const lease = await f.store.acquire(tokenHash(TOKEN));
  await assert.rejects(f.verifier.verify(UID, TOKEN), { code: "failed-precondition" });
  assert.equal(f.acknowledgements(), 0);
  await f.store.release(tokenHash(TOKEN), lease);
  assert.equal(await f.verifier.verify(UID, TOKEN), "active");
});

test("multiple purchases are independent and one refund does not remove another valid purchase", async () => {
  const f = fixture();
  await f.verifier.verify(UID, TOKEN);
  await f.verifier.verify(UID, "second-token");
  f.setPurchase({ purchaseState: 1 });
  await f.verifier.verify(UID, TOKEN);
  assert.equal((await f.verifier.entitlement(UID)).hasPro, true);
  assert.equal((await f.verifier.entitlement("unrelated-user")).hasPro, false);
});

test("RTDN routes one-time purchase cancellations and voided one-time refunds for re-verification", () => {
  for (const notification of [
    { oneTimeProductNotification: { purchaseToken: TOKEN, notificationType: 2 } },
    { voidedPurchaseNotification: { purchaseToken: TOKEN, productType: 2, refundType: 1 } },
  ]) {
    assert.deepEqual(playNotification({ packageName: PACKAGE_NAME, ...notification }), {
      kind: "purchase", token: TOKEN,
    });
  }
});

test("RTDN ignores subscriptions, other apps, and test messages without touching purchases", () => {
  for (const data of [
    null, {}, { packageName: "another.app" },
    { packageName: PACKAGE_NAME, subscriptionNotification: {} },
    { packageName: PACKAGE_NAME, voidedPurchaseNotification: { productType: 1 } },
  ]) {
    assert.deepEqual(playNotification(data), { kind: "ignored" });
  }
  assert.deepEqual(playNotification({ packageName: PACKAGE_NAME, testNotification: {} }), {
    kind: "test",
  });

  assert.throws(() => playNotification({
    packageName: PACKAGE_NAME, oneTimeProductNotification: {},
  }), VerificationError);
});

test("refresh reports partial failures and applies confirmed refunds alongside a failed check", async () => {
  const f = fixture();
  await f.verifier.verify(UID, TOKEN);
  const lease = await f.store.acquire(tokenHash("unverified-token"));
  await f.store.claim(tokenHash("unverified-token"), UID, "unverified-token", lease);
  await f.store.release(tokenHash("unverified-token"), lease);
  f.play.get = async (token) => {
    if (token === TOKEN) return { purchaseState: 1 };
    throw new Error("API unavailable");
  };
  const result = await f.verifier.refresh(UID);
  assert.equal(result.hasPro, false);
  assert.equal(result.verificationIncomplete, true);
  assert.equal(result.expiresAt, null);
});

test("detached purchase restores only to the original verified Google identity", async () => {
  const f = fixture();
  await f.verifier.verify(UID, TOKEN);
  const existing = f.store.records.get(tokenHash(TOKEN));
  assert.ok(existing);
  f.store.records.set(tokenHash(TOKEN), {
    ...existing, uid: "", status: "verifying", verifiedAt: null, expiresAt: null,
  });
  await assert.rejects(f.verifier.verify("new-firebase-user", TOKEN, accountId("wrong-google")), {
    code: "permission-denied",
  });
  assert.equal(await f.verifier.verify("new-firebase-user", TOKEN, accountId(UID)), "active");
  assert.equal((await f.verifier.entitlement("new-firebase-user")).hasPro, true);
  assert.equal((await f.verifier.entitlement(UID)).hasPro, false);
});

test("detached recovery records are retained while valid and removed after confirmed cancellation", async () => {
  const f = fixture();
  await f.verifier.verify(UID, TOKEN);
  const record = await f.store.get(tokenHash(TOKEN));
  assert.ok(record);
  f.store.records.set(tokenHash(TOKEN), { ...record, uid: "", verifiedAt: null, expiresAt: null });
  await f.verifier.reconcileDetached(TOKEN);
  assert.ok(await f.store.get(tokenHash(TOKEN)));
  f.setPurchase({ purchaseState: 1 });
  await f.verifier.reconcileDetached(TOKEN);
  assert.equal(await f.store.get(tokenHash(TOKEN)), null);
});

test("same Google identity cannot transfer a purchase from a still-live Firebase owner", async () => {
  const f = fixture();
  await f.verifier.verify(UID, TOKEN);
  await assert.rejects(f.verifier.verify("new-user", TOKEN, accountId(UID)), { code: "permission-denied" });
});

test("refresh cannot reuse a different Google identity's purchase under the same Firebase UID", async () => {
  const f = fixture();
  await f.verifier.verify(UID, TOKEN);
  await assert.rejects(f.verifier.refresh(UID, accountId("different-google")), { code: "permission-denied" });
  assert.equal((await f.verifier.entitlement(UID, accountId("different-google"))).hasPro, false);
});
