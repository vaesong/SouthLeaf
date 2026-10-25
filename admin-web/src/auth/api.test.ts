import { describe, expect, it, vi } from "vitest";
import { AdminApiError, AdminAuthApi } from "./api";

function response(status: number, body?: unknown, headers: Record<string, string> = {}): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: (name: string) => headers[name] ?? headers[name.toLowerCase()] ?? null },
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

describe("AdminAuthApi", () => {
  it("uses relative no-store requests and keeps the CSRF token only in memory", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "csrf-request",
          data: { csrfToken: "synthetic-csrf", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "login-request",
          data: {
            accountId: "staff",
            permissions: ["ORDER_READ", "CONTENT_READ"],
            expiresAt: "2026-10-24T14:00:00Z",
          },
        }),
      );
    const api = new AdminAuthApi(fetcher as unknown as typeof fetch);

    const identity = await api.login("staff", "  unchanged password  ");

    expect(identity.permissions).toEqual(["CONTENT_READ", "ORDER_READ"]);
    const [csrfPath, csrfInit] = fetcher.mock.calls[0] as unknown as [string, RequestInit];
    const [loginPath, loginInit] = fetcher.mock.calls[1] as unknown as [string, RequestInit];
    expect(csrfPath).toBe("/api/admin/v1/auth/csrf");
    expect(loginPath).toBe("/api/admin/v1/auth/login");
    expect(csrfInit).toMatchObject({ credentials: "same-origin", cache: "no-store" });
    expect(loginInit).toMatchObject({ credentials: "same-origin", cache: "no-store" });
    expect(loginInit.headers).toMatchObject({ "X-CSRF-TOKEN": "synthetic-csrf" });
    expect(JSON.parse(String(loginInit.body))).toEqual({
      accountId: "staff",
      password: "  unchanged password  ",
    });
    expect(JSON.stringify(identity)).not.toContain("synthetic-csrf");
  });

  it("binds the default fetch transport to the browser global", async () => {
    const fetcher = vi.fn(function (this: unknown) {
      expect(this).toBe(globalThis);
      return Promise.resolve(
        response(200, {
          code: "OK",
          requestId: "csrf-request",
          data: { csrfToken: "synthetic-csrf", headerName: "X-CSRF-TOKEN" },
        }),
      );
    });
    vi.stubGlobal("fetch", fetcher);
    try {
      await new AdminAuthApi().getCsrf();
      expect(fetcher).toHaveBeenCalledOnce();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("sends an empty JSON logout with CSRF and waits for the 204 response", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "csrf-request",
          data: { csrfToken: "synthetic-csrf", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockResolvedValueOnce(response(204));
    const api = new AdminAuthApi(fetcher as unknown as typeof fetch);

    await api.logout();

    const [path, init] = fetcher.mock.calls[1] as unknown as [string, RequestInit];
    expect(path).toBe("/api/admin/v1/auth/logout");
    expect(init.method).toBe("POST");
    expect(init.body).toBe("{}");
    expect(init.headers).toMatchObject({ "X-CSRF-TOKEN": "synthetic-csrf" });
  });

  it("retains stable rate-limit metadata for user-facing retry guidance", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "csrf-request",
          data: { csrfToken: "synthetic-csrf", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockResolvedValueOnce(
        response(
          429,
          {
            code: "RATE_LIMITED",
            message: "Too many attempts.",
            requestId: "retry-request",
          },
          { "Retry-After": "17" },
        ),
      );
    const api = new AdminAuthApi(fetcher as unknown as typeof fetch);

    await expect(api.login("staff", "bad")).rejects.toMatchObject({
      status: 429,
      code: "RATE_LIMITED",
      requestId: "retry-request",
      retryAfterSeconds: 17,
    } satisfies Partial<AdminApiError>);
  });
});
