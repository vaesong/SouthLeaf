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
    message.value = "安全初始化暫時無法完成，請檢查連線後重試。";
    requestId.value = error?.requestId ?? "";
  }
}

async function renewCsrfAfterRejection() {
  csrfLoading.value = true;
  const ready = await auth.prepareCsrf(true);
  csrfLoading.value = false;
  message.value = ready
    ? "安全檢查未通過，已更新安全驗證。請重新點擊登入。"
    : "安全檢查未通過，安全初始化仍未完成，請重試。";
}

function safeMessage(error: AdminApiError): string {
  if (error.code === "INVALID_CREDENTIALS") return "帳號或密碼不正確，或帳號目前不可用。";
  if (error.status === 429) {
    return error.retryAfterSeconds
      ? `嘗試次數過多，請等待 ${error.retryAfterSeconds} 秒後再試。`
      : "嘗試次數過多，請稍後再試。";
  }
  if (error.code === "LOGIN_RESULT_UNCONFIRMED") {
    return "登入結果暫時無法確認，請檢查服務狀態後重試。";
  }
  if (error.status === 503 || error.status === 0) return "管理服務暫時無法使用，請稍後重試。";
  if (error.status === 403) return "安全檢查未通過，正在更新安全驗證。";
  return "登入未能完成，請檢查資料後重試。";
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
        : new AdminApiError(0, "NETWORK_ERROR", "登入未能完成。");
    message.value = safeMessage(failure);
    requestId.value = failure.requestId ?? "";
    if (failure.status === 403) void renewCsrfAfterRejection();
  } finally {
    submitting.value = false;
  }
}

onMounted(() => {
  if (route.query.status === "unavailable") {
    message.value = "目前無法確認管理服務狀態，請重試；不會自動重送登入。";
  }
  if (auth.state.status !== "authenticated" && !auth.state.csrfReady)
    void refreshCsrf(false, false);
});
</script>

<template>
  <main class="auth-shell">
    <section class="auth-brand" aria-label="SouthLeaf 管理入口">
      <div class="brand-mark" aria-hidden="true">S</div>
      <p class="eyebrow">SOUTHLEAF · SECURE ADMIN</p>
      <h1>管理入口</h1>
      <p class="brand-copy">使用工作帳戶登入。身份與權限由伺服器即時確認。</p>
      <div class="brand-rule"></div>
      <p class="brand-footnote">安全連線 · 短期工作階段 · 伺服器授權</p>
    </section>

    <section class="auth-panel" aria-labelledby="login-title">
      <div class="panel-kicker">STAFF SIGN IN</div>
      <h2 id="login-title">歡迎回來</h2>
      <p class="panel-copy">請輸入你的工作帳戶資料以繼續。</p>

      <form class="login-form" @submit.prevent="submit">
        <div class="field-group">
          <label for="account-id">帳號標識</label>
          <el-input
            id="account-id"
            v-model="accountId"
            name="username"
            autocomplete="username"
            maxlength="64"
            placeholder="輸入工作帳戶"
            :disabled="submitting"
            required
          />
        </div>
        <div class="field-group">
          <label for="account-password">密碼</label>
          <el-input
            id="account-password"
            v-model="password"
            name="password"
            type="password"
            autocomplete="current-password"
            maxlength="512"
            placeholder="輸入密碼"
            show-password
            :disabled="submitting"
            required
          />
        </div>

        <div v-if="message" class="form-feedback" role="alert" aria-live="assertive">
          <span>{{ message }}</span>
          <small v-if="requestId">Request ID: {{ requestId }}</small>
        </div>

        <div v-if="!auth.state.csrfReady" class="security-status" role="status">
          <span>{{ csrfLoading ? "正在準備安全驗證…" : "需要完成安全初始化才能登入。" }}</span>
          <el-button text :disabled="csrfLoading" @click="refreshCsrf(true)">重試初始化</el-button>
        </div>

        <el-button
          class="submit-button"
          type="primary"
          native-type="submit"
          :loading="submitting"
          :disabled="!canSubmit"
        >
          {{ submitting ? "正在驗證…" : "安全登入" }}
        </el-button>
      </form>

      <div class="panel-divider"><span>SESSION SECURITY</span></div>
      <p class="privacy-note">工作階段由安全 Cookie 管理。此頁不會在瀏覽器儲存密碼或登入令牌。</p>
    </section>
  </main>
</template>
