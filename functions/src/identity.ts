import { HttpsError } from "firebase-functions/v2/https";
import { accountId } from "./verification";

interface VerifiedAuth {
  uid: string;
  token: {
    auth_time?: unknown;
    firebase?: { sign_in_provider?: unknown; identities?: Record<string, unknown> };
  };
}

export function googlePurchaseIdentity(auth: VerifiedAuth | undefined): string {
  if (!auth) throw new HttpsError("unauthenticated", "Sign in before managing Pro.");
  const identities = auth.token.firebase?.identities?.["google.com"];
  if (auth.token.firebase?.sign_in_provider !== "google.com" ||
      !Array.isArray(identities) || identities.length !== 1 ||
      typeof identities[0] !== "string" || identities[0].length === 0) {
    throw new HttpsError("permission-denied", "Sign in with Google to manage this purchase.");
  }
  return accountId(identities[0]);
}

export function requireRecentGoogleAuth(auth: VerifiedAuth | undefined, now = Date.now()): string {
  const identity = googlePurchaseIdentity(auth);
  const time = auth?.token.auth_time;
  if (typeof time !== "number" || !Number.isInteger(time) ||
      time * 1000 > now || now - time * 1000 > 5 * 60 * 1000) {
    throw new HttpsError("failed-precondition", "Sign in with Google again before deleting your account.");
  }
  return identity;
}
