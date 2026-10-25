import { describe, expect, it, vi } from "vitest";
import { AdminOutboxApi, newOutboxIdempotencyKey, OutboxApiError } from "./api";

function response(status: number, body?: unknown): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: () => null },
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const csrf = (token = "csrf-1") =>
  response(200, {
    code: "OK",
    requestId: "csrf-request",
    data: { csrfToken: token, headerName: "X-CSRF-TOKEN" },
  });

const messageId = "00000000-0000-0000-0000-000000000001";
const board = {
  deliveryMode: "TEST_SINK",
  externalDeliveryEnabled: false,
  pendingCount: 2,
  failures: [
    {
      id: messageId,
      intent: "RESERVATION_CONFIRMED",
      status: "FAILED",
      attempts: 4,
      lastError: "PROVIDER_TIMEOUT",
      dueAt: "2026-10-25T00:00:00Z",
    },
  ],
};

describe("AdminOutboxApi", () => {
  it("loads safe outbox data through a same-origin no-store request", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "r", data: board }));
    const api = new AdminOutboxApi(fetcher as unknown as typeof fetch);

    await expect(api.list()).resolves.toEqual(board);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/admin/v1/notifications/outbox",
      expect.objectContaining({
        mode: "same-origin",
        credentials: "same-origin",
        cache: "no-store",
      }),
    );
  });

  it("recovers with CSRF, idempotency key and a fixed safe reason code", async () => {
    const result = {
      commandId: "00000000-0000-0000-0000-000000000002",
      messageId,
      status: "QUEUED",
      replayed: false,
    };
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "r", data: result }));
    const api = new AdminOutboxApi(fetcher as unknown as typeof fetch);
    const key = "a".repeat(32);

    await expect(api.recover(messageId, key)).resolves.toEqual(result);
    const [path, init] = fetcher.mock.calls[1] as unknown as [string, RequestInit];
    expect(path).toBe(`/api/admin/v1/notifications/outbox/${messageId}/recover`);
    expect(init).toMatchObject({ method: "POST", credentials: "same-origin", cache: "no-store" });
    expect(init.headers).toMatchObject({
      "X-CSRF-TOKEN": "csrf-1",
      "Idempotency-Key": key,
      "Content-Type": "application/json",
    });
    expect(init.body).toBe(JSON.stringify({ reasonCode: "OPERATOR_REVIEWED" }));
  });

  it("retries only a definite CSRF rejection with the same command key", async () => {
    const result = {
      commandId: "00000000-0000-0000-0000-000000000002",
      messageId,
      status: "QUEUED",
      replayed: false,
    };
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf("csrf-1"))
      .mockResolvedValueOnce(response(403, { code: "CSRF_REJECTED", requestId: "r1" }))
      .mockResolvedValueOnce(csrf("csrf-2"))
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "r2", data: result }));
    const api = new AdminOutboxApi(fetcher as unknown as typeof fetch);
    const key = "b".repeat(32);

    await api.recover(messageId, key);
    const first = fetcher.mock.calls[1] as unknown as [string, RequestInit];
    const second = fetcher.mock.calls[3] as unknown as [string, RequestInit];
    expect(first[1].headers).toMatchObject({ "Idempotency-Key": key, "X-CSRF-TOKEN": "csrf-1" });
    expect(second[1].headers).toMatchObject({ "Idempotency-Key": key, "X-CSRF-TOKEN": "csrf-2" });
    expect(fetcher).toHaveBeenCalledTimes(4);
  });

  it("marks write network loss unknown and does not blindly replay it", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockRejectedValueOnce(new TypeError("offline"));
    const api = new AdminOutboxApi(fetcher as unknown as typeof fetch);
    const error = await api.recover(messageId, "c".repeat(32)).catch((value: unknown) => value);

    expect(error).toBeInstanceOf(OutboxApiError);
    expect((error as OutboxApiError).outcomeUnknown).toBe(true);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("rejects unsafe transport metadata and malformed response fields", async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(
      response(200, {
        code: "OK",
        requestId: "r",
        data: { ...board, externalDeliveryEnabled: true },
      }),
    );
    const api = new AdminOutboxApi(fetcher as unknown as typeof fetch);
    await expect(api.list()).rejects.toMatchObject({ code: "INVALID_OUTBOX_RESPONSE" });
  });
});

describe("idempotency key helper", () => {
  it("creates a server-compatible 32-hex key", () => {
    const random = { getRandomValues: (bytes: Uint8Array) => bytes.fill(7) } as unknown as Crypto;
    expect(newOutboxIdempotencyKey(random)).toMatch(/^[A-Za-z0-9_-]{16,64}$/);
    expect(newOutboxIdempotencyKey(random)).toHaveLength(32);
  });
});
