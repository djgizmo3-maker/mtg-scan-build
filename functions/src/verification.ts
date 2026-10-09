import { createHash } from "node:crypto";
import { logger } from "firebase-functions";

export const PACKAGE_NAME = "com.mtgscanbuild";
export const PRODUCT_ID = "mtg_pro_unlock";
export const OFFLINE_WINDOW_MS = 30 * 24 * 60 * 60 * 1000;

export class VerificationError extends Error {
  constructor(
    public readonly code: "invalid-argument" | "permission-denied" | "failed-precondition",
    message: string,
  ) {
    super(message);
  }
}

export interface PlayPurchase {
  purchaseState?: number;
  consumptionState?: number;
  acknowledgementState?: number;
  obfuscatedExternalAccountId?: string;
  productId?: string;
  quantity?: number;
}

export interface PlayApi {
  get(token: string): Promise<PlayPurchase>;
  acknowledge(token: string): Promise<void>;
}

export type PurchaseStatus = "verifying" | "active" | "pending" | "revoked";

export interface PurchaseRecord {
  uid: string;
  googleAccountId: string;
  token: string;
  status: PurchaseStatus;
  verifiedAt: number | null;
  expiresAt: number | null;
}

export interface PurchaseStore {
  acquire(hash: string): Promise<string>;
  release(hash: string, lease: string): Promise<void>;
  get(hash: string): Promise<PurchaseRecord | null>;
  claim(hash: string, uid: string, token: string, lease: string, googleAccountId: string): Promise<void>;
  update(hash: string, uid: string, status: PurchaseStatus, now: number, lease: string): Promise<void>;
  forUser(uid: string): Promise<PurchaseRecord[]>;
  removeDetached(hash: string, lease: string): Promise<void>;
}

export function accountId(googleSubject: string): string {
  return createHash("sha256").update(`mtg-pro:google:v1:${googleSubject}`, "utf8").digest("hex");
}

export function tokenHash(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

export function purchaseToken(data: unknown): string {
  if (typeof data !== "object" || data === null || !("purchaseToken" in data)) {
    throw new VerificationError("invalid-argument", "A purchase token is required.");
  }
  const token = data.purchaseToken;
  if (typeof token !== "string" || token.length === 0 || token.length > 4096 ||
      token.trim() !== token || /[\s\x00-\x1f\x7f]/.test(token)) {
    throw new VerificationError("invalid-argument", "The purchase token is invalid.");
  }
  return token;
}

export function playNotification(data: unknown):
  { kind: "ignored" } | { kind: "test" } | { kind: "purchase"; token: string } {
  if (typeof data !== "object" || data === null || !("packageName" in data) ||
      data.packageName !== PACKAGE_NAME) return { kind: "ignored" };
  if ("testNotification" in data) return { kind: "test" };
  if ("oneTimeProductNotification" in data) {
    return { kind: "purchase", token: purchaseToken(data.oneTimeProductNotification) };
  }
  if ("voidedPurchaseNotification" in data) {
    const notification = data.voidedPurchaseNotification;
    if (typeof notification === "object" && notification !== null &&
        "productType" in notification && notification.productType === 2) {
      return { kind: "purchase", token: purchaseToken(notification) };
    }
  }
  return { kind: "ignored" };
}

function state(purchase: PlayPurchase): "active" | "pending" | "revoked" {
  if (purchase.productId !== undefined && purchase.productId !== PRODUCT_ID) {
    throw new VerificationError("failed-precondition", "The purchase is not the Pro product.");
  }
  switch (purchase.purchaseState) {
    case 0: return "active";
    case 1: return "revoked";
    case 2: return "pending";
    default:
      throw new VerificationError("failed-precondition", "Google Play returned an unknown purchase state.");
  }
}

function validatePurchased(purchase: PlayPurchase, googleAccountId: string): void {
  if (purchase.obfuscatedExternalAccountId !== googleAccountId) {
    throw new VerificationError("permission-denied", "The purchase belongs to a different app account.");
  }
  if (purchase.consumptionState !== 0 || (purchase.quantity ?? 1) !== 1 ||
      (purchase.acknowledgementState !== 0 && purchase.acknowledgementState !== 1)) {
    throw new VerificationError("failed-precondition", "The permanent upgrade purchase is invalid.");
  }
}

export class PurchaseVerifier {
  constructor(
    private readonly play: PlayApi,
    private readonly store: PurchaseStore,
    private readonly clock: () => number = Date.now,
  ) {}

  async reconcileDetached(token: string): Promise<void> {
    const hash = tokenHash(token);
    const lease = await this.store.acquire(hash);
    try {
      const record = await this.store.get(hash);
      if (!record || record.uid !== "") return;
      const purchase = await this.play.get(token);
      if (state(purchase) === "revoked") await this.store.removeDetached(hash, lease);
      else if (state(purchase) === "active") validatePurchased(purchase, record.googleAccountId);
    } finally {
      await this.store.release(hash, lease);
    }
  }

  async verify(uid: string, token: string, googleAccountId: string): Promise<"active" | "pending" | "revoked"> {
    const hash = tokenHash(token);
    const lease = await this.store.acquire(hash);
    try {
      return await this.verifyLocked(uid, token, hash, lease, googleAccountId);
    } finally {
      await this.store.release(hash, lease);
    }
  }

  private async verifyLocked(uid: string, token: string, hash: string, lease: string, googleAccountId: string) {
    const existing = await this.store.get(hash);
    if (existing && (existing.googleAccountId !== googleAccountId ||
        (existing.uid !== uid && existing.uid !== ""))) {
      throw new VerificationError("permission-denied", "This purchase is already linked to another account.");
    }
    const purchase = await this.play.get(token);
    const currentState = state(purchase);
    if (currentState !== "active") {
      // Pending/canceled responses may omit account binding. Only update an already claimed token.
      if (existing?.uid === uid) await this.store.update(hash, uid, currentState, this.clock(), lease);
      if (existing?.uid === "" && currentState === "revoked") await this.store.removeDetached(hash, lease);
      return currentState;
    }
    validatePurchased(purchase, googleAccountId);
    await this.store.claim(hash, uid, token, lease, googleAccountId);
    if (purchase.acknowledgementState === 0) {
      await this.play.acknowledge(token);
      const acknowledged = await this.play.get(token);
      const finalState = state(acknowledged);
      if (finalState !== "active") {
        await this.store.update(hash, uid, finalState, this.clock(), lease);
        return finalState;
      }
      validatePurchased(acknowledged, googleAccountId);
      if (acknowledged.acknowledgementState !== 1) {
        throw new VerificationError("failed-precondition", "Google Play has not confirmed acknowledgement yet. Retry verification.");
      }
    }
    await this.store.update(hash, uid, "active", this.clock(), lease);
    return "active";
  }

  async entitlement(uid: string, googleAccountId?: string) {
    const now = this.clock();
    const records = await this.store.forUser(uid);
    const latest = records
      .filter((record) => (googleAccountId === undefined || record.googleAccountId === googleAccountId) &&
        record.status === "active" && record.expiresAt !== null && record.expiresAt > now)
      .sort((a, b) => (b.expiresAt ?? 0) - (a.expiresAt ?? 0))[0];
    return {
      hasPro: latest !== undefined,
      verifiedAt: latest?.verifiedAt ?? null,
      expiresAt: latest?.expiresAt ?? null,
      serverTime: now,
    };
  }

  async refresh(uid: string, googleAccountId: string) {
    let verificationIncomplete = false;
    const records = await this.store.forUser(uid);
    if (records.some((record) => record.googleAccountId !== googleAccountId)) {
      throw new VerificationError("permission-denied", "Sign in with the Google account linked to this purchase.");
    }
    for (const record of records) {
      try {
        await this.verify(uid, record.token, record.googleAccountId);
      } catch (error) {
        verificationIncomplete = true;
        logger.error("A purchase could not be refreshed.", {
          purchaseHash: tokenHash(record.token),
          errorType: error instanceof Error ? error.name : "unknown",
        });
      }
    }
    // Preserve confirmed revocations even if another purchase's API request fails.
    return { ...await this.entitlement(uid, googleAccountId), verificationIncomplete };
  }
}
