export interface UserSummary {
  id: string;
  displayName: string | null;
  displayLabel: string;
  shortId: string;
  named: boolean;
  lastSeenAt: string;
}

export interface UserReservation {
  id: string;
  status: string;
  localDate: string | null;
  localStart: string | null;
  localEnd: string | null;
}

export interface UserVisit {
  id: string;
  page: string;
  contentId: string | null;
  shareCardId: string | null;
  scene: number | null;
  occurredAt: string;
}

export interface UserDetail {
  user: UserSummary;
  note: string | null;
  version: number;
  createdAt: string;
  visitCount: number;
  shareCount: number;
  forwardCount: number;
  reservations: UserReservation[];
  visits: UserVisit[];
}

export interface UserCensus {
  total: number;
  seenToday: number;
  unnamed: number;
  withReservation: number;
  shared: number;
}

export interface UserList {
  users: UserSummary[];
  nextCursor: string | null;
  census: UserCensus;
}

export interface SharePerson {
  userId: string;
  displayLabel: string;
  shortId: string;
  occurredAt: string;
}

export interface ShareChain {
  id: string;
  page: string;
  contentId: string | null;
  chain: { id: string; userId: string; name: string; page: string; createdAt: string }[];
  opens: SharePerson[];
  forwards: SharePerson[];
}

interface Envelope<T> {
  code: string;
  requestId: string;
  data: T;
}

const BASE = "/api/admin/v1";
const CSRF_PATH = "/api/admin/v1/auth/csrf";
const CSRF_HEADER = "X-CSRF-TOKEN";

export class UserApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string,
  ) {
    super(message);
    this.name = "UserApiError";
  }
}

export class AdminUserApi {
  private csrfToken: string | undefined;

  constructor(private readonly fetcher: typeof fetch = globalThis.fetch.bind(globalThis)) {}

  async list(query = "", cursor = ""): Promise<UserList> {
    const params = new URLSearchParams();
    if (query) params.set("q", query);
    if (cursor) params.set("cursor", cursor);
    const queryText = params.toString();
    const suffix = queryText ? `?${queryText}` : "";
    const response = await this.send(`${BASE}/users${suffix}`, { method: "GET" });
    const envelope = await this.readJson<UserList>(response);
    const census = envelope.data?.census;
    if (
      envelope.code !== "OK" ||
      !Array.isArray(envelope.data?.users) ||
      !census ||
      [census.total, census.seenToday, census.unnamed, census.withReservation, census.shared].some(
        (count) => typeof count !== "number",
      )
    ) {
      throw new UserApiError(
        502,
        "INVALID_USER_RESPONSE",
        "用户列表响应无效。",
        envelope.requestId,
      );
    }
    return envelope.data;
  }

  async detail(id: string): Promise<{ user: UserDetail; etag: string }> {
    const response = await this.send(`${BASE}/users/${encodeURIComponent(id)}`, { method: "GET" });
    const envelope = await this.readJson<UserDetail>(response);
    const etag = response.headers.get("etag") ?? "";
    if (envelope.code !== "OK" || envelope.data?.user?.id !== id || !etag) {
      throw new UserApiError(
        502,
        "INVALID_USER_RESPONSE",
        "用户资料响应无效。",
        envelope.requestId,
      );
    }
    return { user: envelope.data, etag };
  }

  async save(
    id: string,
    etag: string,
    displayName: string,
    note: string,
  ): Promise<{ user: UserDetail; etag: string }> {
    const token = await this.csrf();
    const response = await this.send(`${BASE}/users/${encodeURIComponent(id)}`, {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
        [CSRF_HEADER]: token,
        "If-Match": etag,
      },
      body: JSON.stringify({ displayName, note }),
    });
    const envelope = await this.readJson<UserDetail>(response);
    const next = response.headers.get("etag") ?? "";
    if (envelope.code !== "OK" || !next) {
      throw new UserApiError(502, "INVALID_USER_RESPONSE", "保存结果无效。", envelope.requestId);
    }
    return { user: envelope.data, etag: next };
  }

  async share(id: string): Promise<ShareChain> {
    const response = await this.send(`${BASE}/shares/${encodeURIComponent(id)}`, { method: "GET" });
    const envelope = await this.readJson<ShareChain>(response);
    if (envelope.code !== "OK" || envelope.data?.id !== id) {
      throw new UserApiError(
        502,
        "INVALID_USER_RESPONSE",
        "传播路径响应无效。",
        envelope.requestId,
      );
    }
    return envelope.data;
  }

  private async csrf(force = false): Promise<string> {
    if (force) this.csrfToken = undefined;
    if (this.csrfToken) return this.csrfToken;
    const response = await this.send(CSRF_PATH, { method: "GET" });
    const envelope = await this.readJson<{ csrfToken: string; headerName: string }>(response);
    if (
      envelope.code !== "OK" ||
      envelope.data?.headerName !== CSRF_HEADER ||
      typeof envelope.data.csrfToken !== "string"
    ) {
      throw new UserApiError(
        502,
        "INVALID_CSRF_RESPONSE",
        "安全初始化响应无效。",
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
      if (error instanceof UserApiError) throw error;
      throw new UserApiError(0, "NETWORK_ERROR", "无法连接管理服务，请检查连接后重试。");
    }
  }

  private async readJson<T>(response: Response): Promise<Envelope<T>> {
    try {
      return (await response.json()) as Envelope<T>;
    } catch {
      throw new UserApiError(502, "INVALID_USER_RESPONSE", "管理服务响应无效。");
    }
  }

  private async readError(response: Response): Promise<never> {
    let envelope: Partial<Envelope<unknown>> & { message?: string } = {};
    try {
      envelope = (await response.json()) as typeof envelope;
    } catch {
      // Keep the HTTP status when the body is not JSON.
    }
    throw new UserApiError(
      response.status,
      typeof envelope.code === "string" ? envelope.code : "USER_REQUEST_FAILED",
      typeof envelope.message === "string"
        ? envelope.message.slice(0, 200)
        : "用户资料暂时无法读取。",
      typeof envelope.requestId === "string" ? envelope.requestId : undefined,
    );
  }
}
