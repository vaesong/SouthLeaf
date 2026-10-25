import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import { authStoreKey, type AuthStore } from "../auth/store";
import OutboxView from "./OutboxView.vue";

function response(status: number, body: unknown): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: () => null },
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const linkStub = { template: "<a><slot /></a>" };

function auth(permissions: string[]) {
  return {
    state: {
      identity: { accountId: "operator", permissions, expiresAt: "2026-10-25T00:00:00Z" },
    },
  } as unknown as AuthStore;
}

describe("OutboxView", () => {
  it("renders safe dead-letter fields and confirms a local-only recovery", async () => {
    const id = "00000000-0000-0000-0000-000000000001";
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "list-1",
          data: {
            deliveryMode: "TEST_SINK",
            externalDeliveryEnabled: false,
            pendingCount: 0,
            failures: [
              {
                id,
                intent: "RESERVATION_CONFIRMED",
                status: "FAILED",
                attempts: 3,
                lastError: "PROVIDER_TIMEOUT",
                dueAt: "2026-10-25T00:00:00Z",
              },
            ],
          },
        }),
      )
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "csrf",
          data: { csrfToken: "csrf-token", headerName: "X-CSRF-TOKEN" },
        }),
      )
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "recovery",
          data: {
            commandId: "00000000-0000-0000-0000-000000000002",
            messageId: id,
            status: "QUEUED",
            replayed: false,
          },
        }),
      )
      .mockResolvedValueOnce(
        response(200, {
          code: "OK",
          requestId: "list-2",
          data: {
            deliveryMode: "TEST_SINK",
            externalDeliveryEnabled: false,
            pendingCount: 1,
            failures: [],
          },
        }),
      );
    vi.stubGlobal("fetch", fetcher as unknown as typeof fetch);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const wrapper = mount(OutboxView, {
      global: {
        provide: { [authStoreKey as symbol]: auth(["OUTBOX_READ", "OUTBOX_RECOVER"]) },
        stubs: { RouterLink: linkStub },
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain("TEST_SINK");
    expect(wrapper.text()).toContain("RESERVATION_CONFIRMED");
    expect(wrapper.text()).toContain("PROVIDER_TIMEOUT");
    expect(wrapper.text()).not.toContain("recipientRef");
    await wrapper.get("button.recover-button").trigger("click");
    await flushPromises();

    expect(window.confirm).toHaveBeenCalledOnce();
    expect(fetcher).toHaveBeenCalledTimes(4);
    expect(wrapper.text()).toContain("测试接收器不会对外发送通知");
    wrapper.unmount();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("does not render recovery controls without the recovery capability", async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(
      response(200, {
        code: "OK",
        requestId: "list-1",
        data: {
          deliveryMode: "TEST_SINK",
          externalDeliveryEnabled: false,
          pendingCount: 0,
          failures: [
            {
              id: "00000000-0000-0000-0000-000000000003",
              intent: "RESERVATION_CANCELLED",
              status: "FAILED",
              attempts: 1,
              lastError: "TEMPORARY_FAILURE",
              dueAt: "2026-10-25T00:00:00Z",
            },
          ],
        },
      }),
    );
    vi.stubGlobal("fetch", fetcher as unknown as typeof fetch);
    const wrapper = mount(OutboxView, {
      global: {
        provide: { [authStoreKey as symbol]: auth(["OUTBOX_READ"]) },
        stubs: { RouterLink: linkStub },
      },
    });
    await flushPromises();
    expect(wrapper.text()).toContain("缺少恢复能力");
    expect(wrapper.find("button.recover-button").exists()).toBe(false);
    expect(fetcher).toHaveBeenCalledOnce();
    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
