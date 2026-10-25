import { describe, expect, it, vi } from "vitest";
import { AdminContentApi, ContentApiError, etagOf, newIdempotencyKey } from "./api";
import { describeFailure } from "./messages";

function response(status: number, body?: unknown, headers: Record<string, string> = {}): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: (name: string) => headers[name] ?? null },
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const csrf = () =>
  response(200, {
    code: "OK",
    requestId: "c",
    data: { csrfToken: "t1", headerName: "X-CSRF-TOKEN" },
  });

const command = {
  commandId: "cmd-1",
  operation: "PUBLISH",
  status: "SUCCEEDED",
  workId: "w-1",
  targetVersion: 3,
  completedAt: "2026-10-25T00:00:00Z",
  replayed: false,
};

describe("AdminContentApi", () => {
  it("publishes with relative path, CSRF, strong If-Match, idempotency key and an empty body", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "r", data: command }));
    const api = new AdminContentApi(fetcher as unknown as typeof fetch);

    const result = await api.publish("w-1", 3, "k".repeat(32));

    expect(result.commandId).toBe("cmd-1");
    const [path, init] = fetcher.mock.calls[1] as unknown as [string, RequestInit];
    expect(path).toBe("/api/admin/v1/works/w-1/publish");
    expect(init).toMatchObject({
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      body: "{}",
    });
    expect(init.headers).toMatchObject({
      "X-CSRF-TOKEN": "t1",
      "If-Match": '"v3"',
      "Idempotency-Key": "k".repeat(32),
      "Content-Type": "application/json",
    });
  });

  it("retries exactly once after a CSRF rejection with a fresh token and the same key", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(
        response(403, { code: "CSRF_REJECTED", message: "x", requestId: "r1" }),
      )
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "c2",
          data: { csrfToken: "t2", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "r2", data: command }));
    const api = new AdminContentApi(fetcher as unknown as typeof fetch);

    await api.withdraw("w-1", 3, "same-key-0123456789");

    const retry = fetcher.mock.calls[3] as unknown as [string, RequestInit];
    expect(retry[1].headers).toMatchObject({
      "X-CSRF-TOKEN": "t2",
      "Idempotency-Key": "same-key-0123456789",
    });
    expect(fetcher).toHaveBeenCalledTimes(4);
  });

  it("queries unknown catalog command outcomes scoped by the retry key", async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(
      response(200, {
        code: "OK",
        requestId: "r",
        data: {
          commandId: "cmd-catalog",
          releaseId: "rel",
          contentVersion: "catalog-rel",
          revision: 2,
          completedAt: "2026-10-25T00:00:00Z",
          replayed: true,
        },
      }),
    );
    const api = new AdminContentApi(fetcher as unknown as typeof fetch);
    const result = await api.findCatalogCommand("k".repeat(32));
    expect(result.commandId).toBe("cmd-catalog");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/admin/v1/content/catalog/commands",
      expect.objectContaining({
        method: "GET",
        headers: expect.objectContaining({ "Idempotency-Key": "k".repeat(32) }),
      }),
    );
  });

  it("lists immutable catalog versions and loads one through an encoded revision route", async () => {
    const version = { revision: 4, updatedBy: "operator", updatedAt: "2026-10-25T00:00:00Z" };
    const draft = { brand: { name: "synthetic" } };
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "r1", data: [version] }))
      .mockResolvedValueOnce(
        response(200, { code: "OK", requestId: "r2", data: { revision: 4, draft } }),
      );
    const api = new AdminContentApi(fetcher as unknown as typeof fetch);
    await expect(api.listCatalogVersions()).resolves.toEqual([version]);
    await expect(api.getCatalogVersion(4)).resolves.toEqual({ revision: 4, draft });
    expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
      "/api/admin/v1/content/catalog/versions",
      "/api/admin/v1/content/catalog/versions/4",
    ]);
    expect(() => api.getCatalogVersion(0)).toThrow("invalid catalog revision");
  });

  it("saves catalog authoring with the c-revision ETag and the existing CSRF transport", async () => {
    const draft = {
      brand: {
        name: "",
        englishName: "",
        discoveryHeading: "",
        shareTitle: "",
        tagline: "",
        description: "",
        logoMediaId: null,
      },
      contact: { phone: "", address: "", workingHours: "" },
      team: [],
      channels: [],
      businesses: [],
      packages: [],
      featuredBusinessIds: [],
      featuredPackageIds: [],
    } as unknown as import("./api").CatalogDraftInput;
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(
        response(
          200,
          { code: "OK", requestId: "r", data: { revision: 1, draft } },
          { ETag: '"c1"' },
        ),
      );
    const api = new AdminContentApi(fetcher as unknown as typeof fetch);
    await api.saveCatalog(0, draft);
    const [path, init] = fetcher.mock.calls[1] as unknown as [string, RequestInit];
    expect(path).toBe("/api/admin/v1/content/catalog");
    expect(init).toMatchObject({ method: "PUT", body: JSON.stringify(draft) });
    expect(init.headers).toMatchObject({ "If-Match": '"c0"', "X-CSRF-TOKEN": "t1" });
  });

  it("surfaces server failure code and reason without fabricating success", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(
        response(422, {
          code: "PUBLICATION_BLOCKED",
          message: "blocked",
          reason: "AUTHORIZATION_EXPIRED",
          requestId: "r9",
        }),
      );
    const api = new AdminContentApi(fetcher as unknown as typeof fetch);

    const error = await api.publish("w-1", 1, "k".repeat(16)).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ContentApiError);
    expect((error as ContentApiError).reason).toBe("AUTHORIZATION_EXPIRED");
    const described = describeFailure(error);
    expect(described.text).toContain("AUTHORIZATION_EXPIRED");
    expect(described.unknown).toBe(false);
  });

  it("treats network loss as an unknown outcome so the caller keeps its key", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockRejectedValueOnce(new TypeError("offline"));
    const api = new AdminContentApi(fetcher as unknown as typeof fetch);

    const error = await api.createWork("k".repeat(16)).catch((e: unknown) => e);

    expect(describeFailure(error).unknown).toBe(true);
  });

  it("rejects a malformed ETag from the server", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(
        response(200, { code: "OK", requestId: "r", data: { workId: "w" } }, { ETag: 'W/"v2"' }),
      );
    const api = new AdminContentApi(fetcher as unknown as typeof fetch);

    await expect(
      api.saveDraft("w", 1, {
        title: "",
        summary: "",
        subjectScopeRef: "",
        businessIds: [],
        media: [],
        coverMediaId: null,
      }),
    ).rejects.toMatchObject({ code: "INVALID_CONTENT_RESPONSE" });
  });

  it("uploads one multipart file field without setting Content-Type manually", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "r",
          data: { media: { mediaId: "m" }, replayed: false },
        }),
      );
    const api = new AdminContentApi(fetcher as unknown as typeof fetch);

    await api.upload(
      new Blob([new Uint8Array([1, 2, 3])], { type: "image/png" }),
      "a.png",
      "k".repeat(16),
    );

    const [, init] = fetcher.mock.calls[1] as unknown as [string, RequestInit];
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).getAll("file")).toHaveLength(1);
    expect(init.headers).not.toHaveProperty("Content-Type");
  });
});

describe("helpers", () => {
  it("builds strong revision ETags only for positive integers", () => {
    expect(etagOf(7)).toBe('"v7"');
    expect(() => etagOf(0)).toThrow();
    expect(() => etagOf(1.5)).toThrow();
  });

  it("generates 32-hex idempotency keys accepted by the server pattern", () => {
    const key = newIdempotencyKey();
    expect(key).toMatch(/^[A-Za-z0-9_-]{16,64}$/);
    expect(key).toHaveLength(32);
    expect(newIdempotencyKey()).not.toBe(key);
  });
});
