import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { authStoreKey, type AuthStore } from "../auth/store";
import WeekBoard from "./WeekBoard.vue";

function response(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: (name: string) => headers[name] ?? headers[name.toLowerCase()] ?? null },
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const slotId = "00000000-0000-4000-8000-0000000000aa";
const askId = "00000000-0000-4000-8000-0000000090ad";
const session = {
  state: {
    identity: {
      accountId: "operator",
      permissions: ["RESERVATION_READ", "RESERVATION_CONFIRM", "RESERVATION_REJECT", "SLOT_WRITE"],
      expiresAt: "2026-11-30T00:00:00Z",
    },
  },
} as unknown as AuthStore;

function desk(availability = "PENDING", withAsk = true) {
  return {
    code: "OK",
    requestId: "board-1",
    data: {
      slots: [
        {
          id: slotId,
          startAt: "2026-11-23T02:00:00Z",
          endAt: "2026-11-23T04:00:00Z",
          availability,
          revision: 2,
        },
      ],
      asks: withAsk
        ? [
            {
              id: askId,
              slotId,
              startAt: "2026-11-23T02:00:00Z",
              endAt: "2026-11-23T04:00:00Z",
              expiresAt: "2026-11-25T06:38:00Z",
              version: 3,
            },
          ]
        : [],
    },
  };
}

function csrf() {
  return response(200, {
    code: "OK",
    requestId: "csrf",
    data: { csrfToken: "token-1", headerName: "X-CSRF-TOKEN" },
  });
}

function mountBoard() {
  return mount(WeekBoard, {
    global: {
      provide: { [authStoreKey as symbol]: session },
      stubs: { RouterLink: { template: "<a><slot /></a>" } },
    },
  });
}

describe("WeekBoard", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("shows the requested time on the week and confirms that slot", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-11-23T01:00:00.000Z"));
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(200, desk()))
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "confirm-1",
          data: {
            status: "CONFIRMED",
            commandId: "00000000-0000-4000-8000-0000000000bb",
            reservationId: askId,
            orderId: "00000000-0000-4000-8000-0000000000cc",
            slotId,
            orderRevision: 1,
            replayed: false,
          },
        }),
      )
      .mockResolvedValueOnce(response(200, desk("OCCUPIED", false)));
    vi.stubGlobal("fetch", fetcher as unknown as typeof fetch);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const wrapper = mountBoard();
    await flushPromises();

    expect(wrapper.text()).toContain("尾号 90ad");
    expect(wrapper.text()).toContain("10:00");
    await wrapper.get("button.cal-block").trigger("click");
    expect(wrapper.text()).toContain("可以确认");
    await wrapper.get("button.btn-primary").trigger("click");
    await flushPromises();

    const write = fetcher.mock.calls[2]?.[1] as RequestInit;
    expect(write.body).toBe(JSON.stringify({ slotId }));
    expect((write.headers as Record<string, string>)["If-Match"]).toBe('"r3"');
    expect(wrapper.text()).toContain("已确认");
    wrapper.unmount();
  });

  it("opens an empty hour and says which slot was opened", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-11-23T01:00:00.000Z"));
    const opened = {
      id: "00000000-0000-4000-8000-0000000000dd",
      startAt: "2026-11-23T10:00:00Z",
      endAt: "2026-11-23T12:00:00Z",
      revision: 1,
      replayed: false,
    };
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(200, desk("OPEN", false)))
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(response(200, { code: "OK", requestId: "open-1", data: opened }))
      .mockResolvedValueOnce(response(200, desk("OPEN", false)));
    vi.stubGlobal("fetch", fetcher as unknown as typeof fetch);
    const wrapper = mountBoard();
    await flushPromises();

    await wrapper.get('[aria-label="开放 11月23日 18:00"]').trigger("click");
    await flushPromises();

    const write = fetcher.mock.calls[2]?.[1] as RequestInit;
    expect(write.body).toBe(JSON.stringify({ date: "2026-11-23", startTime: "18:00" }));
    expect(wrapper.text()).toContain("已开放");
    expect(wrapper.text()).toContain("18:00–20:00");
    wrapper.unmount();
  });
});
