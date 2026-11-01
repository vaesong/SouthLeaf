import { describe, expect, it, vi } from "vitest";
import { AdminBookingRulesApi, BookingRulesApiError } from "./rulesApi";

function response(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: (name: string) => headers[name] ?? headers[name.toLowerCase()] ?? null },
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const rules = {
  minAdvanceHours: 24,
  maxAdvanceDays: 30,
  pendingExpiryHours: 48,
  slotDurationMinutes: 120,
};

describe("AdminBookingRulesApi", () => {
  it("reads the current version only when the tag matches", async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(
      response(
        200,
        {
          code: "OK",
          requestId: "request-1",
          data: { version: 0, configured: false, rules: null },
        },
        { ETag: '"o0"' },
      ),
    );
    const api = new AdminBookingRulesApi(fetcher);
    await expect(api.current()).resolves.toMatchObject({
      version: 0,
      configured: false,
      etag: '"o0"',
    });
    expect(fetcher).toHaveBeenCalledWith(
      "/api/admin/v1/bookings/operating-rules/current",
      expect.objectContaining({ method: "GET", credentials: "same-origin" }),
    );
  });

  it("saves with the current tag, csrf and one idempotency key", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "csrf",
          data: { csrfToken: "token-1", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockResolvedValueOnce(
        response(
          200,
          { code: "OK", requestId: "saved", data: { version: 1, replayed: false, ...rules } },
          { ETag: '"o1"' },
        ),
      );
    const api = new AdminBookingRulesApi(fetcher);
    const saved = await api.save(0, rules, "a".repeat(16));
    expect(saved.version).toBe(1);
    const [, init] = fetcher.mock.calls[1] as [string, RequestInit];
    expect(init.method).toBe("PUT");
    expect(init.headers).toMatchObject({
      "If-Match": '"o0"',
      "Idempotency-Key": "a".repeat(16),
      "X-CSRF-TOKEN": "token-1",
    });
  });

  it("retries once when the security token is rejected", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "csrf",
          data: { csrfToken: "stale", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockResolvedValueOnce(
        response(403, { code: "CSRF_REJECTED", requestId: "denied", message: "no" }),
      )
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "csrf-2",
          data: { csrfToken: "fresh", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "saved",
          data: { version: 1, replayed: false, ...rules },
        }),
      );
    const api = new AdminBookingRulesApi(fetcher);
    await expect(api.save(0, rules, "b".repeat(16))).resolves.toMatchObject({ version: 1 });
    expect(fetcher).toHaveBeenCalledTimes(4);
  });

  it("keeps a failed save distinct from an unknown outcome", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "csrf",
          data: { csrfToken: "token-1", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockResolvedValueOnce(response(409, { code: "VERSION_CONFLICT", requestId: "stale" }));
    const api = new AdminBookingRulesApi(fetcher);
    await expect(api.save(0, rules, "c".repeat(16))).rejects.toMatchObject({
      status: 409,
      code: "VERSION_CONFLICT",
      outcomeUnknown: false,
    });
    fetcher.mockRejectedValueOnce(new Error("offline"));
    await expect(api.save(0, rules, "c".repeat(16))).rejects.toBeInstanceOf(BookingRulesApiError);
  });
});
