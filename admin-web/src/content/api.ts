import { AdminApiError } from "../auth/api";

export interface VariantView {
  variant: "thumbnail" | "preview";
  width: number;
  height: number;
  byteLength: number;
  contentType: string;
}

export interface MediaView {
  mediaId: string;
  status: string;
  sourceFormat: string;
  sourceWidth: number;
  sourceHeight: number;
  createdAt: string;
  variants: VariantView[];
}

export interface MediaBinding {
  mediaId: string;
  altText: string;
}

export interface WorkDraftInput {
  title: string;
  summary: string;
  subjectScopeRef: string;
  businessIds: string[];
  media: MediaBinding[];
  coverMediaId: string | null;
}

export interface WorkView extends WorkDraftInput {
  workId: string;
  version: number;
  updatedAt: string;
  publicationState: string;
  publishedVersion: number | null;
}

export interface PreviewView {
  work: WorkView;
  publishable: boolean;
  blockingReason: string | null;
  authorizationState: string | null;
  acceptedProvenance: string;
}

export interface CommandView {
  commandId: string;
  operation: "PUBLISH" | "WITHDRAW";
  status: string;
  workId: string;
  targetVersion: number;
  completedAt: string;
  replayed: boolean;
}

export interface CatalogDraftInput {
  brand: {
    name: string;
    englishName: string;
    discoveryHeading: string;
    shareTitle: string;
    tagline: string;
    description: string;
    logoMediaId: string | null;
  };
  contact: { phone: string; address: string; workingHours: string };
  team: Array<{
    id: string;
    name: string;
    role: string;
    bio: string;
    specialties: string[];
    portraitMediaId: string | null;
    position: number;
    published: boolean;
  }>;
  channels: Array<{
    id: string;
    platform: string;
    label: string;
    account: string;
    qrMediaId: string | null;
    qrStatus: string;
    position: number;
    published: boolean;
  }>;
  businesses: Array<{
    id: string;
    name: string;
    summary: string;
    coverMediaId: string | null;
    position: number;
    published: boolean;
  }>;
  packages: Array<{
    id: string;
    businessId: string;
    name: string;
    summary: string;
    suitableFor: string;
    durationMinutes: number | null;
    displayPrice: string;
    priceType: string;
    serviceItems: string[];
    tips: string[];
    notices: string[];
    published: boolean;
    coverMediaId: string | null;
    position: number;
  }>;
  featuredBusinessIds: string[];
  featuredPackageIds: string[];
  featuredWorkIds: string[];
  mediaAltTexts: Record<string, string>;
}
export interface CatalogView {
  revision: number;
  draft: CatalogDraftInput;
}
export interface CatalogVersionView {
  revision: number;
  updatedBy: string;
  updatedAt: string;
}
export interface CatalogPreview {
  publishable: boolean;
  blockingReason: string | null;
}
export interface CatalogCommandView {
  commandId: string;
  releaseId: string;
  contentVersion: string;
  revision: number;
  completedAt: string;
  replayed: boolean;
}

export function catalogEtagOf(revision: number): string {
  if (!Number.isInteger(revision) || revision < 0) throw new Error("invalid catalog revision");
  return `"c${revision}"`;
}

interface Envelope<T> {
  code: string;
  requestId: string;
  data: T;
}

const BASE = "/api/admin/v1";
const CSRF_HEADER = "X-CSRF-TOKEN";
const ETAG = /^"[vc]([0-9]{1,9})"$/;

/** 128-bit random idempotency key; generated per user intent and reused only for its retries. */
export function newIdempotencyKey(random: Crypto = globalThis.crypto): string {
  const bytes = new Uint8Array(16);
  random.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export function etagOf(version: number): string {
  if (!Number.isInteger(version) || version < 1) throw new Error("invalid version");
  return `"v${version}"`;
}

export function mediaContentPath(mediaId: string, variant: "thumbnail" | "preview"): string {
  return `${BASE}/media/${encodeURIComponent(mediaId)}/${variant}/content`;
}

/**
 * Same-origin content transport. The server re-authorizes every request; this client only
 * carries the browser session cookie, the in-memory CSRF token and explicit preconditions.
 */
export class AdminContentApi {
  private csrfToken: string | undefined;

  constructor(private readonly fetcher: typeof fetch = globalThis.fetch.bind(globalThis)) {}

  getCatalog(): Promise<CatalogView> {
    return this.read<CatalogView>("/content/catalog");
  }

  listCatalogVersions(): Promise<CatalogVersionView[]> {
    return this.read<CatalogVersionView[]>("/content/catalog/versions");
  }

  getCatalogVersion(revision: number): Promise<CatalogView> {
    if (!Number.isInteger(revision) || revision < 1) throw new Error("invalid catalog revision");
    return this.read<CatalogView>(`/content/catalog/versions/${revision}`);
  }

  saveCatalog(revision: number, draft: CatalogDraftInput): Promise<CatalogView> {
    return this.write("/content/catalog", {
      method: "PUT",
      json: draft,
      ifMatch: catalogEtagOf(revision),
    });
  }

  previewCatalog(): Promise<CatalogPreview> {
    return this.read<CatalogPreview>("/content/catalog/preview");
  }

  findCatalogCommand(key: string): Promise<CatalogCommandView> {
    return this.read<CatalogCommandView>("/content/catalog/commands", { "Idempotency-Key": key });
  }

  publishCatalog(revision: number, key: string): Promise<CatalogCommandView> {
    return this.write("/content/catalog/publish", {
      method: "POST",
      json: {},
      key,
      ifMatch: catalogEtagOf(revision),
    });
  }

  withdrawCatalog(revision: number, key: string): Promise<{ status: string; revision: number }> {
    return this.write("/content/catalog/withdraw", {
      method: "POST",
      json: {},
      key,
      ifMatch: catalogEtagOf(revision),
    });
  }

  listMedia(): Promise<MediaView[]> {
    return this.read<MediaView[]>("/media");
  }

  async upload(
    file: Blob,
    fileName: string,
    key: string,
  ): Promise<{ media: MediaView; replayed: boolean }> {
    const form = new FormData();
    form.append("file", file, fileName);
    return this.write("/media/uploads", { method: "POST", body: form, key });
  }

  listWorks(): Promise<WorkView[]> {
    return this.read<WorkView[]>("/works");
  }

  createWork(key: string): Promise<{ work: WorkView; replayed: boolean }> {
    return this.write("/works", { method: "POST", json: {}, key });
  }

  getWork(workId: string): Promise<WorkView> {
    return this.read<WorkView>(`/works/${encodeURIComponent(workId)}`);
  }

  saveDraft(workId: string, version: number, draft: WorkDraftInput): Promise<WorkView> {
    return this.write(`/works/${encodeURIComponent(workId)}`, {
      method: "PUT",
      json: draft,
      ifMatch: etagOf(version),
    });
  }

  preview(workId: string): Promise<PreviewView> {
    return this.read<PreviewView>(`/works/${encodeURIComponent(workId)}/preview`);
  }

  publish(workId: string, version: number, key: string): Promise<CommandView> {
    return this.write(`/works/${encodeURIComponent(workId)}/publish`, {
      method: "POST",
      json: {},
      key,
      ifMatch: etagOf(version),
    });
  }

  withdraw(workId: string, version: number, key: string): Promise<CommandView> {
    return this.write(`/works/${encodeURIComponent(workId)}/withdraw`, {
      method: "POST",
      json: {},
      key,
      ifMatch: etagOf(version),
    });
  }

  command(commandId: string): Promise<CommandView> {
    return this.read<CommandView>(`/commands/${encodeURIComponent(commandId)}`);
  }

  private async read<T>(path: string, headers: Record<string, string> = {}): Promise<T> {
    const response = await this.send(`${BASE}${path}`, { method: "GET", headers });
    return this.data<T>(response);
  }

  private async write<T>(
    path: string,
    options: { method: string; json?: unknown; body?: BodyInit; key?: string; ifMatch?: string },
    retried = false,
  ): Promise<T> {
    const token = await this.csrf();
    const headers: Record<string, string> = { [CSRF_HEADER]: token };
    if (options.json !== undefined) headers["Content-Type"] = "application/json";
    if (options.key) headers["Idempotency-Key"] = options.key;
    if (options.ifMatch) headers["If-Match"] = options.ifMatch;
    let response: Response;
    try {
      response = await this.send(`${BASE}${path}`, {
        method: options.method,
        headers,
        body: options.json !== undefined ? JSON.stringify(options.json) : options.body,
      });
    } catch (error) {
      // CSRF rejection happens before any handler runs, so one retry with a fresh token is safe.
      if (
        error instanceof AdminApiError &&
        error.status === 403 &&
        error.code === "CSRF_REJECTED"
      ) {
        this.csrfToken = undefined;
        if (!retried) return this.write<T>(path, options, true);
      }
      throw error;
    }
    const data = await this.data<T>(response);
    const etag = response.headers.get("ETag");
    if (etag !== null && !ETAG.test(etag))
      throw new AdminApiError(502, "INVALID_CONTENT_RESPONSE", "管理服务响应无效。");
    return data;
  }

  private async csrf(): Promise<string> {
    if (this.csrfToken) return this.csrfToken;
    const response = await this.send(`${BASE}/auth/csrf`, { method: "GET" });
    const envelope = (await this.json(response)) as Envelope<{
      csrfToken?: unknown;
      headerName?: unknown;
    }>;
    const token = envelope.data?.csrfToken;
    if (
      envelope.code !== "OK" ||
      envelope.data?.headerName !== CSRF_HEADER ||
      typeof token !== "string" ||
      token.length < 1 ||
      token.length > 4096
    )
      throw new AdminApiError(502, "INVALID_AUTH_RESPONSE", "安全初始化回應無效。");
    this.csrfToken = token;
    return token;
  }

  private async data<T>(response: Response): Promise<T> {
    const envelope = (await this.json(response)) as Envelope<T>;
    if (envelope.code !== "OK" || envelope.data === undefined || envelope.data === null)
      throw new AdminApiError(
        502,
        "INVALID_CONTENT_RESPONSE",
        "管理服务响应无效。",
        envelope.requestId,
      );
    return envelope.data;
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
        headers: { Accept: "application/json", ...(init.headers as Record<string, string>) },
      });
    } catch {
      throw new AdminApiError(
        0,
        "NETWORK_ERROR",
        "无法连接管理服务；结果未知时请以相同操作重试或查询命令状态。",
      );
    }
    if (!response.ok) await this.error(response);
    return response;
  }

  private async json(response: Response): Promise<unknown> {
    try {
      return await response.json();
    } catch {
      throw new AdminApiError(502, "INVALID_CONTENT_RESPONSE", "管理服务响应无效。");
    }
  }

  private async error(response: Response): Promise<never> {
    let envelope: { code?: unknown; message?: unknown; reason?: unknown; requestId?: unknown } = {};
    try {
      envelope = (await response.json()) as typeof envelope;
    } catch {
      // Non-JSON intermediary error: status remains meaningful.
    }
    const code = typeof envelope.code === "string" ? envelope.code : "CONTENT_REQUEST_FAILED";
    const reason = typeof envelope.reason === "string" ? envelope.reason : undefined;
    const message =
      typeof envelope.message === "string"
        ? envelope.message.slice(0, 200)
        : "管理服务暂时无法完成要求。";
    throw new ContentApiError(
      response.status,
      code,
      message,
      typeof envelope.requestId === "string" ? envelope.requestId : undefined,
      reason,
    );
  }
}

export class ContentApiError extends AdminApiError {
  constructor(
    status: number,
    code: string,
    message: string,
    requestId: string | undefined,
    readonly reason: string | undefined,
  ) {
    super(status, code, message, requestId);
    this.name = "ContentApiError";
  }
}
