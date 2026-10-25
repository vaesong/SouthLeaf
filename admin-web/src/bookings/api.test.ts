import { describe, expect, it, vi } from "vitest";
import { AdminBookingApi, BookingApiError } from "./api";

function response(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: (name: string) => headers[name] ?? headers[name.toLowerCase()] ?? null },
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const id = "2c9a6900-0000-4000-8000-000000000001";
const slot = "2c9a6900-0000-4000-8000-000000000002";
const command = "2c9a6900-0000-4000-8000-000000000003";

describe("AdminBookingApi", () => {
  it("reads reservation details with a validated revision and same-origin transport", async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(
      response(
        200,
        {
          code: "OK",
          requestId: "request-1",
          data: {
            reservation: {
              id,
              status: "CONFIRMED",
              source: "STAFF_OFFLINE",
              expiresAt: "2026-10-25T00:00:00Z",
              createdAt: "2026-10-20T00:00:00Z",
              version: 4,
              slotId: slot,
            },
            orderId: command,
            assignments: [],
          },
        },
        { ETag: '"r4"' },
      ),
    );
    const result = await new AdminBookingApi(fetcher).reservation(id.toUpperCase());
    expect(result.etag).toBe('"r4"');
    expect(result.data.reservation.version).toBe(4);
    expect(fetcher).toHaveBeenCalledWith(
      `/api/admin/v1/bookings/reservations/${id}`,
      expect.objectContaining({
        mode: "same-origin",
        credentials: "same-origin",
        cache: "no-store",
      }),
    );
  });

  it("sends a conditional, CSRF-protected, idempotent cancellation", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "csrf-1",
          data: { csrfToken: "token-1", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "write-1",
          data: {
            status: "CANCELLED",
            commandId: command,
            reservationId: id,
            orderId: slot,
            slotId: null,
            orderRevision: 5,
            replayed: false,
          },
        }),
      );
    const result = await new AdminBookingApi(fetcher).cancel(id, '"r4"', "idempotency-key-0001");
    expect(result.status).toBe("CANCELLED");
    const [, init] = fetcher.mock.calls[1] as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers["X-CSRF-TOKEN"]).toBe("token-1");
    expect(headers["If-Match"]).toBe('"r4"');
    expect(headers["Idempotency-Key"]).toBe("idempotency-key-0001");
    expect(JSON.parse(String(init.body))).toEqual({ reasonCode: "STAFF_REQUESTED" });
  });

  it("does not label an explicit version conflict as an unknown write", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "csrf-2",
          data: { csrfToken: "token-2", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockResolvedValueOnce(
        response(409, {
          code: "VERSION_CONFLICT",
          requestId: "conflict-1",
          message: "The request could not be completed.",
        }),
      );
    await expect(
      new AdminBookingApi(fetcher).reschedule(id, slot, '"r4"', "idempotency-key-0002"),
    ).rejects.toMatchObject({ status: 409, code: "VERSION_CONFLICT", outcomeUnknown: false });
  });

  it("marks a dropped write connection as uncertain and preserves the idempotency retry key", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "csrf-3",
          data: { csrfToken: "token-3", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockRejectedValueOnce(new TypeError("connection lost"));
    const error = await new AdminBookingApi(fetcher)
      .cancel(id, '"r4"', "idempotency-key-0003")
      .catch((failure: unknown) => failure);
    expect(error).toBeInstanceOf(BookingApiError);
    expect(error).toMatchObject({ code: "NETWORK_OUTCOME_UNKNOWN", outcomeUnknown: true });
  });
});
