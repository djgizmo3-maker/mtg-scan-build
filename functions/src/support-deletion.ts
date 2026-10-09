import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { AccountDeletion, deleteFirebaseAuthUser } from "./deletion";
import { tokenHash } from "./verification";

interface SupportAccount {
  uid: string;
  emailVerified: boolean;
  providerData: { providerId: string }[];
}

class SupportRequestError extends Error {}

export async function runSupportDeletion(
  args: string[],
  lookup: (email: string) => Promise<SupportAccount>,
  remove: (uid: string) => Promise<void>,
  output: (message: string) => void,
): Promise<void> {
  const email = args[1];
  if ((args.length !== 2 && args.length !== 3) || args[0] !== "--email" ||
      typeof email !== "string" || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ||
      email.length > 320 ||
      (args.length === 3 && args[2] !== "--confirm-verified-owner")) {
    throw new SupportRequestError("Usage: --email REGISTERED_EMAIL [--confirm-verified-owner]. Confirm ownership independently before deletion.");
  }
  const account = await lookup(email);
  if (!account.emailVerified || !account.providerData.some((provider) => provider.providerId === "google.com")) {
    throw new SupportRequestError("The target must be an email-verified Google app account.");
  }
  output(`Matched app account hash: ${tokenHash(account.uid)}`);
  if (args.length === 2) {
    output("Preview only. No data changed. Verify ownership through the registered address before using --confirm-verified-owner.");
    return;
  }
  await remove(account.uid);
  output("App account deletion completed. Confirm completion to the requester and remove resolved support correspondence.");
}

if (require.main === module) {
  initializeApp({ projectId: "mtg-scan-build-7b54f" });
  const auth = getAuth();
  const deletion = new AccountDeletion(getFirestore(), deleteFirebaseAuthUser);
  runSupportDeletion(
    process.argv.slice(2), (email) => auth.getUserByEmail(email),
    (uid) => deletion.start(uid), console.log,
  ).catch((error: unknown) => {
    console.error(error instanceof SupportRequestError ? error.message :
      "Support deletion did not complete. Check operator permissions and pending cleanup before retrying.", {
      errorType: error instanceof Error ? error.name : "unknown",
    });
    process.exitCode = 1;
  });
}
