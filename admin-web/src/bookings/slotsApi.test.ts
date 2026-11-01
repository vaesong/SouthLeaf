import { describe, expect, it, vi } from "vitest";
import { AdminBookingSlotsApi } from "./slotsApi";

function response(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: (name: string) => headers[name] ?? headers[name.toLowerCase()] ?? null },
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

function csrf() {
  return response(200, {
    code: "OK",
    requestId: "csrf",
    data: { csrfToken: "token-1", headerName: "X-CSRF-TOKEN" },
  });
}

const opened = {
  id: "3c9a6900-0000-4000-8000-0000000000aa",
  startAt: "2026-11-03T02:00:00Z",
  endAt: "2026-11-03T04:00:00Z",
  revision: 1,
  replayed: false,
};

describe("AdminBookingSlotsApi", () => {
  it("opens one slot with csrf and one idempotency key", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "opened", data: opened }));
    const api = new AdminBookingSlotsApi(fetcher);
    const slot = await api.open("2026-11-03", "10:00", "a".repeat(16));
    expect(slot.id).toBe(opened.id);
    const [path, init] = fetcher.mock.calls[1] as [string, RequestInit];
    expect(path).toBe("/api/admin/v1/bookings/slots");
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({
      "Idempotency-Key": "a".repeat(16),
      "X-CSRF-TOKEN": "token-1",
    });
    expect(init.body).toBe(JSON.stringify({ date: "2026-11-03", startTime: "10:00" }));
  });

  it("closes with the current revision tag", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(
        response(200, { code: "OK", requestId: "closed", data: { ...opened, revision: 2 } }),
      );
    const api = new AdminBookingSlotsApi(fetcher);
    const slot = await api.close(opened.id, 1, "b".repeat(16));
    expect(slot.revision).toBe(2);
    const [, init] = fetcher.mock.calls[1] as [string, RequestInit];
    expect(init.headers).toMatchObject({ "If-Match": '"r1"', "Idempotency-Key": "b".repeat(16) });
    expect(String(init.body)).toBe("{}");
  });

  it("keeps each week result, including a refusal", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "week",
          data: [
            {
              date: "2026-11-03",
              startTime: "10:00",
              ok: false,
              reason: "SLOT_OVERLAP",
              slot: null,
            },
            { date: "2026-11-04", startTime: "10:00", ok: true, reason: null, slot: opened },
          ],
        }),
      );
    const api = new AdminBookingSlotsApi(fetcher);
    const items = await api.openWeek(["2026-11-03", "2026-11-04"], ["10:00"], "c".repeat(16));
    expect(items.map((item) => item.ok)).toEqual([false, true]);
  });

  it("says to save a rule before opening slots", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      response(422, {
        code: "RULES_NOT_CONFIGURED",
        requestId: "rules",
        message: "请先设置预约规则",
      }),
    );
    const api = new AdminBookingSlotsApi(fetcher);
    await expect(
      api.board("2026-11-02T00:00:00.000Z", "2026-11-09T00:00:00.000Z"),
    ).rejects.toMatchObject({ code: "RULES_NOT_CONFIGURED", message: "请先设置预约规则" });
  });
});
