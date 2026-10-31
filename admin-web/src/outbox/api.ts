export interface OutboxFailure {
  id: string;
  intent: string;
  status: "FAILED";
  attempts: number;
  lastError: string;
  dueAt: string;
}

export interface OutboxBoard {
  deliveryMode: "TEST_SINK";
  externalDeliveryEnabled: false;
  pendingCount: number;
  failures: OutboxFailure[];
}

export interface OutboxRecovery {
  commandId: string;
  messageId: string;
  status: "QUEUED";
  replayed: boolean;
}

interface Envelope<T> {
  code: string;
  requestId: string;
  data: T;
}

interface ErrorEnvelope {
  code: string;
  requestId: string;
  message?: string;
}

const BASE = "/api/admin/v1/notifications/outbox";
const CSRF_PATH = "/api/admin/v1/auth/csrf";
const CSRF_HEADER = "X-CSRF-TOKEN";

export class OutboxApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string,
    readonly outcomeUnknown = false,
  ) {
    super(message);
    this.name = "OutboxApiError";
  }
}

export function newOutboxIdempotencyKey(random: Crypto = globalThis.crypto): string {
  const bytes = new Uint8Array(16);
  random.getRandomValues(bytes);
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
}

/** Same-origin outbox transport; write outcomes are never blindly replayed after network loss. */
export class AdminOutboxApi {
  private csrfToken: string | undefined;

  constructor(private readonly fetcher: typeof fetch = globalThis.fetch.bind(globalThis)) {}

  async list(): Promise<OutboxBoard> {
    const response = await this.send(BASE, { method: "GET" });
    const envelope = (await this.readJson<OutboxBoard>(response)) as Envelope<OutboxBoard>;
    const value = envelope.data;
    if (
      envelope.code !== "OK" ||
      value?.deliveryMode !== "TEST_SINK" ||
      value.externalDeliveryEnabled !== false ||
      !Number.isInteger(value.pendingCount) ||
      value.pendingCount < 0 ||
      !Array.isArray(value.failures) ||
      !value.failures.every(validFailure)
    ) {
      throw new OutboxApiError(
        502,
        "INVALID_OUTBOX_RESPONSE",
        "通知待办响应无效。",
        envelope.requestId,
      );
    }
    return value;
  }

  async recover(messageId: string, idempotencyKey: string): Promise<OutboxRecovery> {
    if (!/^[0-9a-f-]{36}$/.test(messageId)) throw new Error("invalid outbox message id");
    if (!/^[A-Za-z0-9_-]{16,64}$/.test(idempotencyKey)) throw new Error("invalid idempotency key");
    const body = JSON.stringify({ reasonCode: "OPERATOR_REVIEWED" });
    let retriedCsrf = false;
    for (;;) {
      const token = await this.csrf(retriedCsrf);
      let response: Response;
      try {
        response = await this.send(`${BASE}/${encodeURIComponent(messageId)}/recover`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            [CSRF_HEADER]: token,
            "Idempotency-Key": idempotencyKey,
          },
          body,
        });
      } catch (error) {
        if (
          error instanceof OutboxApiError &&
          error.status === 403 &&
          error.code === "CSRF_REJECTED" &&
          !retriedCsrf
        ) {
          this.csrfToken = undefined;
          retriedCsrf = true;
          continue;
        }
        if (error instanceof OutboxApiError)
          throw new OutboxApiError(error.status, error.code, error.message, error.requestId, true);
        throw error;
      }
      try {
        const envelope = (await this.readJson<OutboxRecovery>(
          response,
        )) as Envelope<OutboxRecovery>;
        if (envelope.code !== "OK" || !validRecovery(envelope.data, messageId))
          throw new OutboxApiError(
            502,
            "INVALID_OUTBOX_RESPONSE",
            "通知待办响应无效。",
            envelope.requestId,
          );
        return envelope.data;
      } catch (error) {
        if (error instanceof OutboxApiError)
          throw new OutboxApiError(error.status, error.code, error.message, error.requestId, true);
        throw error;
      }
    }
  }

  private async csrf(force = false): Promise<string> {
    if (force) this.csrfToken = undefined;
    if (this.csrfToken) return this.csrfToken;
    const response = await this.send(CSRF_PATH, { method: "GET" });
    const envelope = (await this.readJson<{ csrfToken: string; headerName: string }>(
      response,
    )) as Envelope<{ csrfToken: string; headerName: string }>;
    if (
      envelope.code !== "OK" ||
      envelope.data?.headerName !== CSRF_HEADER ||
      typeof envelope.data.csrfToken !== "string" ||
      envelope.data.csrfToken.length < 1 ||
      envelope.data.csrfToken.length > 4096
    ) {
      throw new OutboxApiError(
        502,
        "INVALID_CSRF_RESPONSE",
        "安全初始化回應無效。",
        envelope.requestId,
      );
    }
    this.csrfToken = envelope.data.csrfToken;
    return this.csrfToken;
  }

  private async send(path: string, init: RequestInit): Promise<Response> {
    try {
      const response = await this.fetcher(path, {
        ...init,
        mode: "same-origin",
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
        headers: { Accept: "application/json", ...init.headers },
      });
      if (!response.ok) await this.readError(response);
      return response;
    } catch (error) {
      if (error instanceof OutboxApiError) throw error;
      throw new OutboxApiError(0, "NETWORK_ERROR", "无法连接管理服务，请检查连接后重试。");
    }
  }

  private async readJson<T>(response: Response): Promise<Envelope<T>> {
    if (!response.ok) await this.readError(response);
    try {
      return (await response.json()) as Envelope<T>;
    } catch {
      throw new OutboxApiError(502, "INVALID_OUTBOX_RESPONSE", "管理服务响应无效。");
    }
  }

  private async readError(response: Response): Promise<never> {
    let envelope: Partial<ErrorEnvelope> = {};
    try {
      envelope = (await response.json()) as Partial<ErrorEnvelope>;
    } catch {
      // Keep the status even when an intermediary does not return JSON.
    }
    throw new OutboxApiError(
      response.status,
      typeof envelope.code === "string" ? envelope.code : "OUTBOX_REQUEST_FAILED",
      typeof envelope.message === "string"
        ? envelope.message.slice(0, 200)
        : "通知待办暂时无法读取。",
      typeof envelope.requestId === "string" ? envelope.requestId : undefined,
    );
  }
}

function validFailure(value: unknown): value is OutboxFailure {
  if (!value || typeof value !== "object") return false;
  const row = value as Partial<OutboxFailure>;
  return (
    typeof row.id === "string" &&
    /^[0-9a-f-]{36}$/.test(row.id) &&
    typeof row.intent === "string" &&
    /^[A-Z][A-Z0-9_]{0,63}$/.test(row.intent) &&
    row.status === "FAILED" &&
    Number.isInteger(row.attempts) &&
    (row.attempts ?? -1) >= 0 &&
    typeof row.lastError === "string" &&
    /^[A-Z][A-Z0-9_]{0,63}$/.test(row.lastError) &&
    typeof row.dueAt === "string" &&
    !Number.isNaN(Date.parse(row.dueAt))
  );
}

function validRecovery(value: unknown, expectedId: string): value is OutboxRecovery {
  if (!value || typeof value !== "object") return false;
  const result = value as Partial<OutboxRecovery>;
  return (
    typeof result.commandId === "string" &&
    /^[0-9a-f-]{36}$/.test(result.commandId) &&
    result.messageId === expectedId &&
    result.status === "QUEUED" &&
    typeof result.replayed === "boolean"
  );
}
