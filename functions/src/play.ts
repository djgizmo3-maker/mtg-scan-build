import { GoogleAuth } from "google-auth-library";
import { logger } from "firebase-functions";
import { PACKAGE_NAME, PRODUCT_ID, PlayApi, PlayPurchase } from "./verification";

function logPlayFailure(error: unknown, operation: string, stage: string): void {
  const response = typeof error === "object" && error !== null && "response" in error
    ? error.response : undefined;
  const status = typeof response === "object" && response !== null && "status" in response
    ? response.status : undefined;
  const code = typeof error === "object" && error !== null && "code" in error
    ? error.code : undefined;
  const data: unknown = typeof response === "object" && response !== null && "data" in response
    ? response.data : undefined;
  const apiError = typeof data === "object" && data !== null && "error" in data
    ? data.error : undefined;
  const errors = typeof apiError === "object" && apiError !== null && "errors" in apiError
    ? apiError.errors : undefined;
  const allowedReasons = new Set([
    "authError", "required", "insufficientPermissions", "forbidden",
    "accessNotConfigured", "permissionDenied", "invalid", "notFound",
  ]);
  const apiReasons = Array.isArray(errors) ? [...new Set(errors.flatMap((entry: unknown) => {
    if (typeof entry !== "object" || entry === null || !("reason" in entry) ||
        typeof entry.reason !== "string" || !allowedReasons.has(entry.reason)) return [];
    return [entry.reason];
  }))] : [];
  const apiStatus = typeof apiError === "object" && apiError !== null && "status" in apiError
    ? apiError.status : undefined;
  // Never log raw API errors: they can include purchase URLs, tokens, and credentials.
  logger.error("Google Play API operation failed.", {
    operation, stage,
    httpStatus: typeof status === "number" && Number.isInteger(status) ? status : null,
    apiReasons,
    apiStatus: typeof apiStatus === "string" &&
      ["UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND", "INVALID_ARGUMENT",
        "FAILED_PRECONDITION", "UNAVAILABLE", "RESOURCE_EXHAUSTED"].includes(apiStatus)
      ? apiStatus : null,
    transportCode: typeof code === "string" &&
      ["ENOTFOUND", "ETIMEDOUT", "ECONNRESET", "ECONNREFUSED", "EAI_AGAIN"].includes(code)
      ? code : null,
  });
}

export class AndroidPublisher implements PlayApi {
  private readonly auth = new GoogleAuth({
    scopes: ["https://www.googleapis.com/auth/androidpublisher"],
  });

  private url(token: string): string {
    return `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE_NAME}` +
      `/purchases/products/${PRODUCT_ID}/tokens/${encodeURIComponent(token)}`;
  }

  async get(token: string): Promise<PlayPurchase> {
    let stage = "credentials";
    try {
      const client = await this.auth.getClient();
      stage = "purchase-query";
      const result = await client.request<PlayPurchase>({ url: this.url(token), timeout: 15000 });
      return result.data;
    } catch (error) {
      logPlayFailure(error, "get", stage);
      throw error;
    }
  }

  async acknowledge(token: string): Promise<void> {
    let stage = "credentials";
    try {
      const client = await this.auth.getClient();
      stage = "acknowledgement";
      await client.request({
        url: `${this.url(token)}:acknowledge`, method: "POST", data: {}, timeout: 15000,
      });
    } catch (error) {
      logPlayFailure(error, "acknowledge", stage);
      throw error;
    }
  }
}
