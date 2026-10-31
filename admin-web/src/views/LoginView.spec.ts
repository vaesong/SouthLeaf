import { defineComponent, h } from "vue";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import { describe, expect, it, vi } from "vitest";
import { AdminApiError, type AdminAuthApi, type AdminIdentity } from "../auth/api";
import { authStoreKey, createAuthStore } from "../auth/store";
import LoginView from "./LoginView.vue";

const testIdentity: AdminIdentity = {
  accountId: "staff",
  permissions: ["CONTENT_READ"],
  expiresAt: "2026-10-24T14:00:00Z",
};

const InputStub = defineComponent({
  inheritAttrs: false,
  props: { modelValue: String, type: String },
  emits: ["update:modelValue"],
  setup(props, { attrs, emit }) {
    return () =>
      h("input", {
        ...attrs,
        type: props.type === "password" ? "password" : "text",
        value: props.modelValue ?? "",
        onInput: (event: Event) =>
          emit("update:modelValue", (event.target as HTMLInputElement).value),
      });
  },
});

const ButtonStub = defineComponent({
  inheritAttrs: false,
  props: { nativeType: String, disabled: Boolean, loading: Boolean },
  setup(props, { attrs, slots }) {
    return () =>
      h(
        "button",
        {
          ...attrs,
          type: props.nativeType ?? "button",
          disabled: props.disabled || props.loading,
        },
        slots.default?.(),
      );
  },
});

function setupView(loginResult: () => Promise<AdminIdentity>) {
  const api = {
    getSession: vi.fn().mockRejectedValue(new AdminApiError(401, "AUTHENTICATION_REQUIRED", "")),
    getCsrf: vi.fn().mockResolvedValue(undefined),
    login: vi.fn().mockImplementation(loginResult),
    logout: vi.fn().mockResolvedValue(undefined),
  } as unknown as AdminAuthApi;
  const auth = createAuthStore(api);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/login", component: LoginView },
      { path: "/", component: defineComponent({ template: "<main>Identity home</main>" }) },
    ],
  });
  const Root = defineComponent({ render: () => h(RouterView) });

  return { api, auth, router, Root };
}

describe("LoginView", () => {
  it("keeps a password out of storage and clears it after successful login", async () => {
    const { api, auth, router, Root } = setupView(() => Promise.resolve(testIdentity));
    await auth.checkSession();
    await router.push("/login?returnTo=%2F");
    const wrapper = mount(Root, {
      global: {
        plugins: [router],
        provide: { [authStoreKey as symbol]: auth },
        stubs: { "el-input": InputStub, "el-button": ButtonStub },
      },
    });
    await flushPromises();

    await wrapper.get('input[name="username"]').setValue("staff");
    await wrapper.get('input[name="password"]').setValue("exact password");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(api.login).toHaveBeenCalledWith("staff", "exact password");
    expect(auth.state.status).toBe("authenticated");
    expect(router.currentRoute.value.path).toBe("/");
    expect(wrapper.text()).toContain("Identity home");
    wrapper.unmount();
  });

  it("shows one stable credential error and clears the password field", async () => {
    const { api, auth, router, Root } = setupView(() =>
      Promise.reject(new AdminApiError(401, "INVALID_CREDENTIALS", "synthetic secret details")),
    );
    await auth.checkSession();
    await router.push("/login");
    const wrapper = mount(Root, {
      global: {
        plugins: [router],
        provide: { [authStoreKey as symbol]: auth },
        stubs: { "el-input": InputStub, "el-button": ButtonStub },
      },
    });
    await flushPromises();

    await wrapper.get('input[name="username"]').setValue("staff");
    const password = wrapper.get<HTMLInputElement>('input[name="password"]');
    await password.setValue("wrong password");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("账号或密码不正确，或账号目前不可用。");
    expect(wrapper.text()).not.toContain("synthetic secret details");
    expect(password.element.value).toBe("");
    wrapper.unmount();
  });
});
