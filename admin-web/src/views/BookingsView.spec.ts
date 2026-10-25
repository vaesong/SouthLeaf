import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import { authStoreKey, type AuthStore } from "../auth/store";
import BookingsView from "./BookingsView.vue";

function response(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: (name: string) => headers[name] ?? headers[name.toLowerCase()] ?? null },
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const reservationId = "3c9a6900-0000-4000-8000-000000000001";
const orderId = "3c9a6900-0000-4000-8000-000000000002";
const session = {
  state: {
    identity: {
      accountId: "operator",
      permissions: ["RESERVATION_READ", "ORDER_READ", "RESERVATION_CANCEL"],
      expiresAt: "2026-10-25T00:00:00Z",
    },
  },
} as unknown as AuthStore;

function reservation(status = "CONFIRMED") {
  return {
    reservation: {
      id: reservationId,
      status,
      source: "STAFF_OFFLINE",
      expiresAt: "2026-10-25T00:00:00Z",
      createdAt: "2026-10-20T00:00:00Z",
      version: status === "CANCELLED" ? 5 : 4,
      slotId: status === "CANCELLED" ? null : orderId,
    },
    orderId,
    assignments: [],
  };
}

const order = {
  id: orderId,
  reservationId,
  lifecycle: "ACTIVE",
  fulfilment: "SCHEDULED",
  currentRevision: 4,
  createdAt: "2026-10-20T00:00:00Z",
  reservationStatus: "CONFIRMED",
  snapshot: {
    packageVersionRef: "package-v1",
    packageDisplayName: "Demo session",
    currencyCode: "JPY",
    amountMinorUnits: 12000,
    priceVersion: "price-v1",
    slotId: orderId,
    startAt: "2026-11-10T01:00:00Z",
    endAt: "2026-11-10T02:00:00Z",
    businessZone: "Asia/Tokyo",
    displayOffset: "+09:00",
    scheduleRevision: 2,
    source: "STAFF_OFFLINE",
    capturedAt: "2026-10-20T00:00:00Z",
  },
  revisions: [],
};

const linkStub = { template: "<a><slot /></a>" };

describe("BookingsView", () => {
  it("loads an opaque reservation and order snapshot, then confirms a local cancellation", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "pending-1", data: [] }))
      .mockResolvedValueOnce(
        response(
          200,
          { code: "OK", requestId: "reservation-1", data: reservation() },
          { ETag: '"r4"' },
        ),
      )
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "order-1", data: order }))
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
          requestId: "cancel-1",
          data: {
            status: "CANCELLED",
            commandId: "3c9a6900-0000-4000-8000-000000000003",
            reservationId,
            orderId,
            slotId: null,
            orderRevision: 5,
            replayed: false,
          },
        }),
      )
      .mockResolvedValueOnce(
        response(
          200,
          { code: "OK", requestId: "reservation-2", data: reservation("CANCELLED") },
          { ETag: '"r5"' },
        ),
      )
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "order-2", data: order }))
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "pending-2", data: [] }));
    vi.stubGlobal("fetch", fetcher as unknown as typeof fetch);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const wrapper = mount(BookingsView, {
      global: { provide: { [authStoreKey as symbol]: session }, stubs: { RouterLink: linkStub } },
    });
    await flushPromises();

    await wrapper.get("#reservation-id").setValue(reservationId);
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("Demo session");
    expect(wrapper.text()).not.toContain("customerId");

    await wrapper.get("button.danger-button").trigger("click");
    await flushPromises();
    expect(window.confirm).toHaveBeenCalledOnce();
    expect(wrapper.text()).toContain("取消已記錄");
    expect(fetcher).toHaveBeenCalledTimes(8);
    const [, write] = fetcher.mock.calls[4] as [string, RequestInit];
    const headers = write.headers as Record<string, string>;
    expect(headers["If-Match"]).toBe('"r4"');
    expect(headers["Idempotency-Key"]).toBeTruthy();
    wrapper.unmount();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("reuses the same idempotency key after an uncertain write on the current page", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "pending-1", data: [] }))
      .mockResolvedValueOnce(
        response(
          200,
          { code: "OK", requestId: "reservation-1", data: reservation() },
          { ETag: '"r4"' },
        ),
      )
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "order-1", data: order }))
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "csrf-1",
          data: { csrfToken: "token-1", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockResolvedValueOnce(
        response(503, { code: "BOOKING_SERVICE_UNAVAILABLE", requestId: "uncertain-1" }),
      )
      .mockResolvedValueOnce(
        response(
          200,
          { code: "OK", requestId: "reservation-2", data: reservation() },
          { ETag: '"r4"' },
        ),
      )
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "order-2", data: order }))
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "pending-2", data: [] }))
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "cancel-2",
          data: {
            status: "CANCELLED",
            commandId: "3c9a6900-0000-4000-8000-000000000003",
            reservationId,
            orderId,
            slotId: null,
            orderRevision: 5,
            replayed: false,
          },
        }),
      )
      .mockResolvedValueOnce(
        response(
          200,
          { code: "OK", requestId: "reservation-3", data: reservation("CANCELLED") },
          { ETag: '"r5"' },
        ),
      )
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "order-3", data: order }))
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "pending-3", data: [] }));
    vi.stubGlobal("fetch", fetcher as unknown as typeof fetch);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const wrapper = mount(BookingsView, {
      global: { provide: { [authStoreKey as symbol]: session }, stubs: { RouterLink: linkStub } },
    });
    await flushPromises();
    await wrapper.get("#reservation-id").setValue(reservationId);
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    await wrapper.get("button.danger-button").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("操作結果尚未確認");

    await wrapper.get("button.danger-button").trigger("click");
    await flushPromises();
    const firstWrite = fetcher.mock.calls[4]?.[1] as RequestInit;
    const retryWrite = fetcher.mock.calls[8]?.[1] as RequestInit;
    expect((firstWrite.headers as Record<string, string>)["Idempotency-Key"]).toBe(
      (retryWrite.headers as Record<string, string>)["Idempotency-Key"],
    );
    expect(wrapper.text()).toContain("取消已記錄");
    wrapper.unmount();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("does not expose actions without the respective server capabilities", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "pending-1", data: [] }));
    vi.stubGlobal("fetch", fetcher as unknown as typeof fetch);
    const wrapper = mount(BookingsView, {
      global: {
        provide: { [authStoreKey as symbol]: { state: { identity: { permissions: [] } } } },
        stubs: { RouterLink: linkStub },
      },
    });
    await flushPromises();
    expect(wrapper.text()).toContain("缺少預約查閱能力");
    expect(wrapper.find("button.danger-button").exists()).toBe(false);
    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
