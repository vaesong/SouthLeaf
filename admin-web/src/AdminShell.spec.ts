import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it, vi } from "vitest";
import AdminShell from "./AdminShell.vue";
import { authStoreKey, type AuthStore } from "./auth/store";
import type { AdminContentApi } from "./content/api";
import { createCatalogStore, catalogStoreKey } from "./content/catalogStore";

describe("AdminShell logout", () => {
  it("asks for confirmation before logging out", async () => {
    const logout = vi.fn().mockResolvedValue(undefined);
    const auth = {
      state: {
        status: "authenticated",
        identity: {
          accountId: "operator",
          permissions: [],
          expiresAt: "2026-11-01T00:00:00Z",
        },
        action: null,
      },
      logout,
    } as unknown as AuthStore;
    const catalog = createCatalogStore({
      getCatalog: vi.fn(),
    } as unknown as AdminContentApi);
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/", component: { template: "<div />" } },
        { path: "/login", name: "login", component: { template: "<div />" } },
      ],
    });
    await router.push("/");
    const wrapper = mount(AdminShell, {
      global: {
        plugins: [router],
        provide: {
          [authStoreKey as symbol]: auth,
          [catalogStoreKey as symbol]: catalog,
        },
      },
    });
    await flushPromises();
    await wrapper.get("button.account-btn").trigger("click");
    await wrapper.get("button.logout").trigger("click");
    expect(logout).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("确认结束这次登录");
    await wrapper.get("button.btn-danger").trigger("click");
    await flushPromises();
    expect(logout).toHaveBeenCalledOnce();
    wrapper.unmount();
  });
});
