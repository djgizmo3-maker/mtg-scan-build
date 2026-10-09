import assert from "node:assert/strict";
import { test } from "node:test";
import { runSupportDeletion } from "./support-deletion";

test("support deletion is preview-only unless independently verified ownership is explicitly confirmed", async () => {
  const messages: string[] = [];
  let deleted = false;
  await runSupportDeletion(["--email", "owner@example.test"], async (email) => {
    assert.equal(email, "owner@example.test");
    return { uid: "owner", emailVerified: true, providerData: [{ providerId: "google.com" }] };
  }, async () => { deleted = true; }, (message) => messages.push(message));
  assert.equal(deleted, false);
  assert.ok(messages.some((message) => message.includes("Preview only")));
  assert.ok(messages.every((message) => !message.includes("owner@example.test")));
});

test("verified support deletion targets only the account looked up by its registered address", async () => {
  let deletedUid = "";
  await runSupportDeletion(["--email", "owner@example.test", "--confirm-verified-owner"], async () => ({
    uid: "verified-owner", emailVerified: true, providerData: [{ providerId: "google.com" }],
  }), async (uid) => { deletedUid = uid; }, () => {});
  assert.equal(deletedUid, "verified-owner");
});

test("support deletion rejects invalid arguments and non-Google or unverified accounts", async () => {
  const invalidArgs = [[], ["--uid", "attacker"], ["--email", "bad"], ["--email", "a@example.test", "--force"]];
  for (const args of invalidArgs) {
    await assert.rejects(runSupportDeletion(args, async () => {
      throw new Error("Must not look up invalid input");
    }, async () => { assert.fail("Must not delete invalid input"); }, () => {}), /Usage:/);
  }
  for (const account of [
    { uid: "owner", emailVerified: false, providerData: [{ providerId: "google.com" }] },
    { uid: "owner", emailVerified: true, providerData: [{ providerId: "password" }] },
  ]) {
    await assert.rejects(runSupportDeletion(
      ["--email", "owner@example.test", "--confirm-verified-owner"],
      async () => account, async () => { assert.fail("Must not delete"); }, () => {},
    ), /email-verified Google/);
  }
});
