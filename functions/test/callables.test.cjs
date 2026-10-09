const assert = require("node:assert/strict");
const { test } = require("node:test");

process.env.GCLOUD_PROJECT = "demo-mtg-pro";
const functions = require("../lib/index.js");
const { accountId } = require("../lib/verification.js");
const { FirestorePurchaseStore } = require("../lib/store.js");
const { requireRecentGoogleAuth, googlePurchaseIdentity } = require("../lib/identity.js");
const { AndroidPublisher } = require("../lib/play.js");
const { GoogleAuth } = require("google-auth-library");
const { logger } = require("firebase-functions");
const googleAuth = (uid, subject = "google-subject") => ({
  uid, token: { auth_time: Math.floor(Date.now() / 1000),
    firebase: { sign_in_provider: "google.com", identities: { "google.com": [subject] } } },
});

test("all callable handlers reject unauthenticated requests", async () => {
  for (const name of ["proPurchaseAccount", "verifyProPurchase", "refreshProEntitlement", "deleteProAccount"]) {
    await assert.rejects(async () => functions[name].run({ data: {}, auth: undefined }), {
      code: "unauthenticated",
    });
  }
});

test("verify callable rejects invalid input before database or Play access", async () => {
  await assert.rejects(async () => functions.verifyProPurchase.run({
    data: {}, auth: { uid: "test" },
  }), { code: "invalid-argument" });
});

test("checkout account ID is derived from auth, not supplied client identity", async (t) => {
  t.mock.method(FirestorePurchaseStore.prototype, "assertAccountActive", async (uid) => {
    assert.equal(uid, "test");
  });
  const result = await functions.proPurchaseAccount.run({
    data: { uid: "attacker", obfuscatedAccountId: "override" }, auth: googleAuth("test"),
  });
  assert.deepEqual(result, { obfuscatedAccountId: accountId("google-subject") });
});

test("stable Google binding survives Firebase account recreation and rejects non-Google sessions", () => {
  assert.equal(googlePurchaseIdentity(googleAuth("old")), googlePurchaseIdentity(googleAuth("new")));
  assert.notEqual(googlePurchaseIdentity(googleAuth("old")), googlePurchaseIdentity(googleAuth("other", "other-subject")));
  assert.throws(() => googlePurchaseIdentity({ uid: "test", token: {} }), { code: "permission-denied" });
});

test("account deletion requires Google authentication within exactly five minutes", () => {
  const auth = googleAuth("test");
  const now = auth.token.auth_time * 1000;
  assert.equal(requireRecentGoogleAuth(auth, now + 300000), accountId("google-subject"));
  assert.throws(() => requireRecentGoogleAuth(auth, now + 300001), { code: "failed-precondition" });
  assert.throws(() => requireRecentGoogleAuth(auth, now - 1), { code: "failed-precondition" });
});

test("malformed Google claims and stale deletion authentication fail before backend access", async () => {
  for (const firebase of [
    { sign_in_provider: "password", identities: { "google.com": ["subject"] } },
    { sign_in_provider: "google.com", identities: { "google.com": [] } },
    { sign_in_provider: "google.com", identities: { "google.com": ["one", "two"] } },
    { sign_in_provider: "google.com", identities: { "google.com": [42] } },
  ]) {
    assert.throws(() => googlePurchaseIdentity({ uid: "user", token: { firebase } }), { code: "permission-denied" });
  }
  const auth = googleAuth("test");
  auth.token.auth_time -= 301;
  await assert.rejects(functions.deleteProAccount.run({ data: {}, auth }), { code: "failed-precondition" });
});

test("Play diagnostics identify failed operations without logging API secrets", async (t) => {
  const failure = Object.assign(new Error("secret purchase URL and authorization"), {
    code: "secret-token",
    response: { status: 403, data: { purchaseToken: "secret-token" } },
  });
  const logs = [];
  t.mock.method(logger, "error", (...args) => logs.push(args));
  t.mock.method(GoogleAuth.prototype, "getClient", async () => ({
    async request() { throw failure; },
  }));
  const publisher = new AndroidPublisher();
  await assert.rejects(publisher.get("secret-token"), (error) => error === failure);
  await assert.rejects(publisher.acknowledge("secret-token"), (error) => error === failure);
  assert.deepEqual(logs, [
    ["Google Play API operation failed.", {
      operation: "get", stage: "purchase-query", httpStatus: 403, transportCode: null,
      apiReasons: [], apiStatus: null,
    }],
    ["Google Play API operation failed.", {
      operation: "acknowledge", stage: "acknowledgement", httpStatus: 403, transportCode: null,
      apiReasons: [], apiStatus: null,
    }],
  ]);
});

test("Play credential failures are logged safely and rethrown unchanged", async (t) => {
  const failure = Object.assign(new Error("secret credentials"), { code: "ENOTFOUND" });
  const logs = [];
  t.mock.method(logger, "error", (...args) => logs.push(args));
  t.mock.method(GoogleAuth.prototype, "getClient", async () => { throw failure; });
  await assert.rejects(new AndroidPublisher().get("secret-token"), (error) => error === failure);
  assert.deepEqual(logs, [["Google Play API operation failed.", {
    operation: "get", stage: "credentials", httpStatus: null, transportCode: "ENOTFOUND",
    apiReasons: [], apiStatus: null,
  }]]);
});

test("Play diagnostics allow only known API reasons and statuses, not response messages", async (t) => {
  const logs = [];
  t.mock.method(logger, "error", (...args) => logs.push(args));
  const failure = Object.assign(new Error("secret"), { response: {
    status: 401, data: { error: {
      message: "secret authorization and purchase token", status: "UNAUTHENTICATED",
      errors: [{ reason: "authError" }, { reason: "insufficientPermissions" },
        { reason: "authError" }, { reason: "secret-token" }, null, "secret"],
    } },
  } });
  t.mock.method(GoogleAuth.prototype, "getClient", async () => ({
    async request() { throw failure; },
  }));
  await assert.rejects(new AndroidPublisher().get("secret-token"), (error) => error === failure);
  assert.deepEqual(logs, [["Google Play API operation failed.", {
    operation: "get", stage: "purchase-query", httpStatus: 401, transportCode: null,
    apiReasons: ["authError", "insufficientPermissions"], apiStatus: "UNAUTHENTICATED",
  }]]);
  failure.response.data.error.status = "secret-status";
  await assert.rejects(new AndroidPublisher().get("secret-token"), (error) => error === failure);
  assert.equal(logs[1][1].apiStatus, null);
  assert.equal(JSON.stringify(logs).includes("secret"), false);
});
