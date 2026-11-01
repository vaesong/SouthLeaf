import { newBookingIdempotencyKey } from "./api";

export interface OperatingRules {
  minAdvanceHours: number;
  maxAdvanceDays: number;
  pendingExpiryHours: number;
  slotDurationMinutes: number;
}

export interface OperatingRuleCurrent {
  version: number;
  configured: boolean;
  rules: OperatingRules | null;
  etag: string;
}

export interface OperatingRuleVersion {
  version: number;
  rules: OperatingRules;
  updatedAt: string;
}

export interface OperatingRuleSave extends OperatingRules {
  version: number;
  replayed: boolean;
}

interface Envelope<T> {
  code: string;
  requestId: string;
  data: T;
}

interface ErrorEnvelope {
  code: string;
  requestId?: string;
}

const CURRENT = "/api/admin/v1/bookings/operating-rules/current";
const VERSIONS = "/api/admin/v1/bookings/operating-rules/versions";
const SAVE = "/api/admin/v1/bookings/operating-rules";
const CSRF_PATH = "/api/admin/v1/auth/csrf";
const CSRF_HEADER = "X-CSRF-TOKEN";
const RULE_ETAG = /^"o(?:0|[1-9][0-9]{0,8})"$/;

export class BookingRulesApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string,
    readonly outcomeUnknown = false,
  ) {
    super(message);
    this.name = "BookingRulesApiError";
  }
}

export { newBookingIdempotencyKey };

/** Same-origin rule transport. Writes carry CSRF, the current version and one idempotency key. */
export class AdminBookingRulesApi {
  private csrfToken: string | undefined;

  constructor(private readonly fetcher: typeof fetch = globalThis.fetch.bind(globalThis)) {}

  async current(): Promise<OperatingRuleCurrent> {
    const response = await this.send(CURRENT, { method: "GET" });
    const envelope = await this.read<OperatingRuleCurrent>(response);
    const etag = response.headers.get("ETag") ?? "";
    if (
      envelope.code !== "OK" ||
      !validCurrent(envelope.data) ||
      !RULE_ETAG.test(etag) ||
      etag !== `"o${envelope.data.version}"`
    )
      throw invalidResponse(envelope.requestId);
    return { ...envelope.data, etag };
  }

  async versions(): Promise<OperatingRuleVersion[]> {
    const envelope = await this.read<OperatingRuleVersion[]>(
      await this.send(VERSIONS, { method: "GET" }),
    );
    if (
      envelope.code !== "OK" ||
      !Array.isArray(envelope.data) ||
      !envelope.data.every(validVersion)
    )
      throw invalidResponse(envelope.requestId);
    return envelope.data;
  }

  async save(version: number, rules: OperatingRules, key: string): Promise<OperatingRuleSave> {
    if (!Number.isInteger(version) || version < 0) throw new Error("invalid rule version");
    if (!/^[A-Za-z0-9_-]{16,64}$/.test(key)) throw new Error("invalid idempotency key");
    const etag = `"o${version}"`;
    let retriedCsrf = false;
    for (;;) {
      const token = await this.csrf(retriedCsrf);
      let response: Response;
      try {
        response = await this.send(SAVE, {
          method: "PUT",
          headers: {
            "Content-Type": "application/json",
            [CSRF_HEADER]: token,
            "If-Match": etag,
            "Idempotency-Key": key,
          },
          body: JSON.stringify(rules),
        });
      } catch (error) {
        if (
          error instanceof BookingRulesApiError &&
          error.status === 403 &&
          error.code === "CSRF_REJECTED" &&
          !retriedCsrf
        ) {
          this.csrfToken = undefined;
          retriedCsrf = true;
          continue;
        }
        throw unknownIfNeeded(error);
      }
      const envelope = await this.read<OperatingRuleSave>(response);
      const saved = envelope.data;
      if (
        envelope.code !== "OK" ||
        !validRules(saved) ||
        !Number.isInteger(saved.version) ||
        typeof saved.replayed !== "boolean"
      )
        throw invalidResponse(envelope.requestId);
      return saved;
    }
  }

  private async csrf(force = false): Promise<string> {
    if (force) this.csrfToken = undefined;
    if (this.csrfToken) return this.csrfToken;
    const envelope = await this.read<{ csrfToken: string; headerName: string }>(
      await this.send(CSRF_PATH, { method: "GET" }),
    );
    if (
      envelope.code !== "OK" ||
      envelope.data?.headerName !== CSRF_HEADER ||
      typeof envelope.data.csrfToken !== "string" ||
      envelope.data.csrfToken.length < 1
    )
      throw invalidResponse(envelope.requestId);
    this.csrfToken = envelope.data.csrfToken;
    return this.csrfToken;
  }

  private async send(path: string, init: RequestInit): Promise<Response> {
    let response: Response;
    try {
      response = await this.fetcher(path, {
        ...init,
        mode: "same-origin",
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
        headers: { Accept: "application/json", ...init.headers },
      });
    } catch {
      throw new BookingRulesApiError(
        0,
        "NETWORK_OUTCOME_UNKNOWN",
        "这次保存没能确认结果。",
        undefined,
        init.method !== "GET",
      );
    }
    if (!response.ok) {
      const body = await safeJson<ErrorEnvelope>(response);
      throw new BookingRulesApiError(
        response.status,
        typeof body?.code === "string" ? body.code : "REQUEST_FAILED",
        ruleErrorMessage(response.status),
        body?.requestId,
        false,
      );
    }
    return response;
  }

  private async read<T>(response: Response): Promise<Envelope<T>> {
    const body = await safeJson<Envelope<T>>(response);
    if (
      !body ||
      typeof body.code !== "string" ||
      typeof body.requestId !== "string" ||
      !("data" in body)
    )
      throw invalidResponse(typeof body?.requestId === "string" ? body.requestId : undefined);
    return body;
  }
}

function unknownIfNeeded(error: unknown): never {
  if (error instanceof BookingRulesApiError) {
    if (error.status === 0 || error.status >= 500) {
      throw new BookingRulesApiError(
        error.status,
        error.code,
        error.message,
        error.requestId,
        true,
      );
    }
    throw error;
  }
  throw new BookingRulesApiError(
    0,
    "NETWORK_OUTCOME_UNKNOWN",
    "这次保存没能确认结果。",
    undefined,
    true,
  );
}

function validRules(value: unknown): value is OperatingRules {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  return (
    Number.isInteger(item.minAdvanceHours) &&
    Number.isInteger(item.maxAdvanceDays) &&
    Number.isInteger(item.pendingExpiryHours) &&
    Number.isInteger(item.slotDurationMinutes)
  );
}

function validCurrent(value: unknown): value is Omit<OperatingRuleCurrent, "etag"> {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  return (
    Number.isInteger(item.version) &&
    typeof item.configured === "boolean" &&
    (item.rules === null || validRules(item.rules)) &&
    item.configured === (item.rules !== null)
  );
}

function validVersion(value: unknown): value is OperatingRuleVersion {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  return (
    Number.isInteger(item.version) && validRules(item.rules) && typeof item.updatedAt === "string"
  );
}

async function safeJson<T>(response: Response): Promise<T | null> {
  try {
    return (await response.json()) as T;
  } catch {
    return null;
  }
}

function invalidResponse(requestId?: string) {
  return new BookingRulesApiError(
    502,
    "INVALID_BOOKING_RESPONSE",
    "预约规则暂时读不出来。",
    requestId,
  );
}

function ruleErrorMessage(status: number) {
  if (status === 401) return "登录工作阶段已失效，请重新登录。";
  if (status === 403) return "当前账号不能修改预约规则。";
  if (status === 409) return "规则已在别处更新，请重新打开后再保存。";
  if (status === 400 || status === 413 || status === 415 || status === 428)
    return "规则内容或版本不符合要求，请重新打开后再保存。";
  if (status >= 500) return "预约规则暂时无法保存。";
  return "预约规则暂时无法保存。";
}
