export interface AdminIdentity {
  accountId: string;
  permissions: string[];
  expiresAt: string;
}

interface EnvelopeBase {
  code: string;
  requestId: string;
}

interface CsrfEnvelope extends EnvelopeBase {
  data: {
    csrfToken: string;
    headerName: "X-CSRF-TOKEN";
  };
}

interface IdentityEnvelope extends EnvelopeBase {
  data: AdminIdentity;
}

interface ErrorEnvelope extends EnvelopeBase {
  message: string;
}

export class AdminApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string,
    readonly retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = "AdminApiError";
  }
}

const AUTH_PATH = "/api/admin/v1/auth";
const CSRF_HEADER = "X-CSRF-TOKEN";

/** Same-origin auth transport; the browser, not application code, owns the session cookie. */
export class AdminAuthApi {
  private csrfToken: string | undefined;

  constructor(private readonly fetcher: typeof fetch = globalThis.fetch.bind(globalThis)) {}

  async getCsrf(force = false): Promise<void> {
    if (force) this.csrfToken = undefined;
    if (this.csrfToken) return;
    const response = await this.send(`${AUTH_PATH}/csrf`, { method: "GET" });
    const envelope = (await this.readJson(response)) as CsrfEnvelope;
    if (
      envelope.code !== "OK" ||
      envelope.data?.headerName !== CSRF_HEADER ||
      typeof envelope.data.csrfToken !== "string" ||
      envelope.data.csrfToken.length < 1 ||
      envelope.data.csrfToken.length > 4096
    ) {
      throw new AdminApiError(502, "INVALID_AUTH_RESPONSE", "安全初始化回應無效。");
    }
    this.csrfToken = envelope.data.csrfToken;
  }

  async getSession(): Promise<AdminIdentity> {
    const response = await this.send(`${AUTH_PATH}/session`, { method: "GET" });
    const envelope = (await this.readJson(response)) as IdentityEnvelope;
    return this.identityFrom(envelope);
  }

  async login(accountId: string, password: string): Promise<AdminIdentity> {
    await this.getCsrf();
    const token = this.csrfToken;
    if (!token) throw new AdminApiError(503, "CSRF_UNAVAILABLE", "安全初始化尚未完成。");
    const response = await this.send(`${AUTH_PATH}/login`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        [CSRF_HEADER]: token,
      },
      body: JSON.stringify({ accountId, password }),
    });
    const envelope = (await this.readJson(response)) as IdentityEnvelope;
    const identity = this.identityFrom(envelope);
    // Successful login rotates the CSRF cookie; fetch a fresh token before later writes.
    this.csrfToken = undefined;
    return identity;
  }

  async logout(): Promise<void> {
    await this.getCsrf();
    const token = this.csrfToken;
    if (!token) throw new AdminApiError(503, "CSRF_UNAVAILABLE", "安全初始化尚未完成。");
    const response = await this.send(`${AUTH_PATH}/logout`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        [CSRF_HEADER]: token,
      },
      body: "{}",
    });
    if (response.status !== 204) {
      throw new AdminApiError(502, "INVALID_AUTH_RESPONSE", "退出回應無效。");
    }
    this.csrfToken = undefined;
  }

  private identityFrom(envelope: IdentityEnvelope): AdminIdentity {
    const identity = envelope.data;
    if (
      envelope.code !== "OK" ||
      !identity ||
      typeof identity.accountId !== "string" ||
      !Array.isArray(identity.permissions) ||
      !identity.permissions.every((permission) => typeof permission === "string") ||
      typeof identity.expiresAt !== "string" ||
      Number.isNaN(Date.parse(identity.expiresAt))
    ) {
      throw new AdminApiError(502, "INVALID_AUTH_RESPONSE", "身份回應無效。", envelope.requestId);
    }
    return {
      accountId: identity.accountId,
      permissions: [...new Set(identity.permissions)].sort(),
      expiresAt: identity.expiresAt,
    };
  }

  private async send(path: string, init: RequestInit): Promise<Response> {
    try {
      const response = await this.fetcher(path, {
        ...init,
        mode: "same-origin",
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
        headers: {
          Accept: "application/json",
          ...init.headers,
        },
      });
      if (!response.ok && response.status !== 204) await this.readError(response);
      return response;
    } catch (error) {
      if (error instanceof AdminApiError) throw error;
      throw new AdminApiError(0, "NETWORK_ERROR", "无法连接管理服务，请检查连接后重试。");
    }
  }

  private async readJson(
    response: Response,
  ): Promise<EnvelopeBase | IdentityEnvelope | CsrfEnvelope> {
    if (!response.ok) await this.readError(response);
    try {
      return (await response.json()) as EnvelopeBase | IdentityEnvelope | CsrfEnvelope;
    } catch {
      throw new AdminApiError(502, "INVALID_AUTH_RESPONSE", "管理服务响应无效。");
    }
  }

  private async readError(response: Response): Promise<never> {
    let envelope: Partial<ErrorEnvelope> = {};
    try {
      envelope = (await response.json()) as Partial<ErrorEnvelope>;
    } catch {
      // The status remains useful even if an intermediary returned a non-JSON error page.
    }
    const retryHeader = response.headers.get("Retry-After");
    const retryAfterSeconds =
      retryHeader && /^\d+$/.test(retryHeader) ? Number(retryHeader) : undefined;
    throw new AdminApiError(
      response.status,
      typeof envelope.code === "string" ? envelope.code : "AUTH_REQUEST_FAILED",
      typeof envelope.message === "string"
        ? envelope.message.slice(0, 200)
        : "管理服务暂时无法完成要求。",
      typeof envelope.requestId === "string" ? envelope.requestId : undefined,
      retryAfterSeconds,
    );
  }
}
