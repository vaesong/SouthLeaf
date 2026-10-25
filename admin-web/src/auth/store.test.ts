import { describe, expect, it, vi } from "vitest";
import { AdminApiError, type AdminAuthApi, type AdminIdentity } from "./api";
import { createAuthStore } from "./store";

const identity: AdminIdentity = {
  accountId: "staff",
  permissions: ["CONTENT_READ"],
  expiresAt: "2026-10-24T14:00:00Z",
};

function fakeApi(overrides: Record<string, unknown> = {}) {
  return {
    getSession: vi.fn().mockResolvedValue(identity),
    getCsrf: vi.fn().mockResolvedValue(undefined),
    login: vi.fn().mockResolvedValue(identity),
    logout: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  } as unknown as AdminAuthApi;
}

describe("auth state machine", () => {
  it("distinguishes an unavailable session service from an anonymous session", async () => {
    const api = fakeApi({
      getSession: vi
        .fn()
        .mockRejectedValue(new AdminApiError(503, "AUTH_SERVICE_UNAVAILABLE", "offline")),
    });
    const auth = createAuthStore(api);

    await auth.checkSession();

    expect(auth.state.status).toBe("unavailable");
    expect(auth.state.identity).toBeNull();
  });

  it("recovers a timed-out login by checking the current session once, without resubmitting", async () => {
    const api = fakeApi({
      login: vi.fn().mockRejectedValue(new AdminApiError(0, "NETWORK_ERROR", "timeout")),
      getSession: vi.fn().mockResolvedValue(identity),
    });
    const auth = createAuthStore(api);

    await expect(auth.login("staff", "temporary-password")).resolves.toEqual(identity);

    expect(api.login).toHaveBeenCalledTimes(1);
    expect(api.getSession).toHaveBeenCalledTimes(1);
    expect(auth.state.status).toBe("authenticated");
  });

  it("does not represent a failed server revocation as a successful logout", async () => {
    const api = fakeApi({
      logout: vi
        .fn()
        .mockRejectedValue(new AdminApiError(503, "AUTH_SERVICE_UNAVAILABLE", "offline")),
    });
    const auth = createAuthStore(api);
    await auth.checkSession();
    await auth.prepareCsrf();

    await expect(auth.logout()).rejects.toMatchObject({ status: 503 });

    expect(auth.state.status).toBe("authenticated");
    expect(auth.state.identity).toEqual(identity);
  });
});
