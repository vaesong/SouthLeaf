<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { AdminApiError } from "../auth/api";
import { authStoreKey } from "../auth/store";
import { safeReturnPath } from "../auth/navigation";

function requireAuthStore() {
  const store = inject(authStoreKey);
  if (!store) throw new Error("AuthStore was not provided");
  return store;
}

const auth = requireAuthStore();
const route = useRoute();
const router = useRouter();
const accountId = ref("");
const password = ref("");
const submitting = ref(false);
const csrfLoading = ref(false);
const message = ref("");
const requestId = ref("");

const canSubmit = computed(
  () =>
    accountId.value.length > 0 &&
    password.value.length > 0 &&
    auth.state.csrfReady &&
    !csrfLoading.value &&
    !submitting.value,
);

async function refreshCsrf(force = false, clearFeedback = true) {
  csrfLoading.value = true;
  if (clearFeedback) {
    message.value = "";
    requestId.value = "";
  }
  const ready = await auth.prepareCsrf(force);
  csrfLoading.value = false;
  if (!ready) {
    const error = auth.state.error;
    message.value = "安全初始化暂时无法完成，请检查连接后重试。";
    requestId.value = error?.requestId ?? "";
  }
}

async function renewCsrfAfterRejection() {
  csrfLoading.value = true;
  const ready = await auth.prepareCsrf(true);
  csrfLoading.value = false;
  message.value = ready
    ? "安全检查未通过，已更新安全验证。请重新点击登录。"
    : "安全检查未通过，安全初始化仍未完成，请重试。";
}

function safeMessage(error: AdminApiError): string {
  if (error.code === "INVALID_CREDENTIALS") return "账号或密码不正确，或账号目前不可用。";
  if (error.status === 429) {
    return error.retryAfterSeconds
      ? `尝试次数过多，请等待 ${error.retryAfterSeconds} 秒后再试。`
      : "尝试次数过多，请稍后再试。";
  }
  if (error.code === "LOGIN_RESULT_UNCONFIRMED") {
    return "登录结果暂时无法确认，请检查服务状态后重试。";
  }
  if (error.status === 503 || error.status === 0) return "管理服务暂时无法使用，请稍后重试。";
  if (error.status === 403) return "安全检查未通过，正在更新安全验证。";
  return "登录未能完成，请检查资料后重试。";
}

async function submit() {
  if (!canSubmit.value) return;
  submitting.value = true;
  message.value = "";
  requestId.value = "";
  try {
    await auth.login(accountId.value, password.value);
    password.value = "";
    await router.replace(safeReturnPath(route.query.returnTo));
  } catch (error) {
    password.value = "";
    const failure =
      error instanceof AdminApiError
        ? error
        : new AdminApiError(0, "NETWORK_ERROR", "登录未能完成。");
    message.value = safeMessage(failure);
    requestId.value = failure.requestId ?? "";
    if (failure.status === 403) void renewCsrfAfterRejection();
  } finally {
    submitting.value = false;
  }
}

onMounted(() => {
  if (route.query.status === "unavailable") {
    message.value = "目前无法确认管理服务状态，请重试；不会自动重发登录。";
  }
  if (auth.state.status !== "authenticated" && !auth.state.csrfReady)
    void refreshCsrf(false, false);
});
</script>

<template>
  <main class="login-screen">
    <section class="card login-wrap">
      <div class="login-art" aria-hidden="true">
        <div class="ph"></div>
        <div class="txt">
          <small>枝南影像</small>
          <b>欢迎回来</b>
          <span>登录后继续今天的工作。</span>
        </div>
      </div>
      <form class="login-form" @submit.prevent="submit">
        <div class="brand" style="padding: 0">
          <div class="brand-mark">枝</div>
          <div><b>枝南影像</b><small>只使用已授权的工作账号</small></div>
        </div>
        <h1>登录后台</h1>
        <div class="field">
          <label for="account-id">账号</label>
          <input
            id="account-id"
            v-model="accountId"
            class="input"
            name="username"
            autocomplete="username"
            maxlength="64"
            placeholder="请输入账号"
            :disabled="submitting"
            required
          />
        </div>
        <div class="field">
          <label for="account-password">密码</label>
          <input
            id="account-password"
            v-model="password"
            class="input"
            name="password"
            type="password"
            autocomplete="current-password"
            maxlength="512"
            placeholder="请输入密码"
            :disabled="submitting"
            required
          />
        </div>
        <p v-if="message" class="alert-line" role="alert">
          {{ message }}<small v-if="requestId">请求编号 {{ requestId }}</small>
        </p>
        <p v-if="!auth.state.csrfReady" role="status">
          {{ csrfLoading ? "正在准备安全验证…" : "需要完成安全初始化才能登录。" }}
          <button
            class="btn btn-ghost btn-sm"
            type="button"
            :disabled="csrfLoading"
            @click="refreshCsrf(true)"
          >
            重试初始化
          </button>
        </p>
        <button
          class="btn btn-primary btn-block"
          type="submit"
          style="height: 44px"
          :disabled="!canSubmit"
        >
          {{ submitting ? "正在验证…" : "登录" }}
        </button>
        <p class="hint">连续多次失败会暂时锁定。忘记密码请联系技术人员重置，本页不能修改密码。</p>
      </form>
    </section>
  </main>
</template>
