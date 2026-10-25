<script setup lang="ts">
import { computed, inject, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { AdminApiError } from "../auth/api";
import { authStoreKey } from "../auth/store";

function requireAuthStore() {
  const store = inject(authStoreKey);
  if (!store) throw new Error("AuthStore was not provided");
  return store;
}

const auth = requireAuthStore();
const router = useRouter();
const notice = ref("");
const requestId = ref("");
const identity = computed(() => auth.state.identity);
const expiry = computed(() => {
  const value = identity.value?.expiresAt;
  if (!value) return "—";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString("zh-HK");
});

watch(
  () => auth.state.status,
  (status) => {
    if (status === "anonymous" || status === "unavailable") {
      void router.replace({
        name: "login",
        query: { status: status === "unavailable" ? "unavailable" : undefined },
      });
    }
  },
);

async function retryCsrf() {
  notice.value = "";
  const ready = await auth.prepareCsrf(true);
  if (!ready) {
    notice.value = "安全初始化未能完成，請檢查連線後重試。";
    requestId.value = auth.state.error?.requestId ?? "";
  }
}

async function logout() {
  notice.value = "";
  requestId.value = "";
  try {
    await auth.logout();
    await router.replace({ name: "login" });
  } catch (error) {
    const failure = error instanceof AdminApiError ? error : null;
    notice.value = "伺服器端退出尚未確認。請重試；目前工作階段仍視為有效。";
    requestId.value = failure?.requestId ?? "";
  }
}
</script>

<template>
  <main class="identity-shell">
    <header class="topbar">
      <router-link class="wordmark" to="/" aria-label="SouthLeaf 管理首頁">
        <span class="wordmark-mark">S</span>
        <span>SouthLeaf <small>ADMIN</small></span>
      </router-link>
      <div class="topbar-status">
        <span class="environment-badge">S1-D · 本地驗證（非生產）</span>
        <span class="secure-label"><span class="secure-dot"></span>安全工作階段</span>
      </div>
    </header>

    <section class="identity-card" aria-labelledby="identity-title">
      <div class="identity-heading">
        <div class="identity-symbol" aria-hidden="true">✓</div>
        <div>
          <p class="eyebrow">CURRENT SESSION</p>
          <h1 id="identity-title">已安全登入</h1>
          <p class="panel-copy">以下身份資訊由伺服器目前的工作階段回傳。</p>
        </div>
      </div>

      <dl class="identity-details">
        <div>
          <dt>帳號標識</dt>
          <dd>{{ identity?.accountId ?? "—" }}</dd>
        </div>
        <div>
          <dt>工作階段到期</dt>
          <dd>{{ expiry }}</dd>
        </div>
      </dl>

      <section class="permission-section" aria-labelledby="permissions-title">
        <div class="section-title-row">
          <h2 id="permissions-title">伺服器授予的能力</h2>
          <span>{{ identity?.permissions.length ?? 0 }}</span>
        </div>
        <ul v-if="identity?.permissions.length" class="permission-list">
          <li v-for="permission in identity.permissions" :key="permission">{{ permission }}</li>
        </ul>
        <p v-else class="empty-permissions">目前沒有可顯示的能力項目。</p>
        <p class="helper-text">能力摘要僅供檢視；每個請求仍由伺服器重新授權。</p>
      </section>

      <div v-if="notice" class="form-feedback" role="alert" aria-live="assertive">
        <span>{{ notice }}</span>
        <small v-if="requestId">Request ID: {{ requestId }}</small>
      </div>

      <div v-if="!auth.state.csrfReady" class="security-status" role="status">
        <span>退出前需要安全初始化。</span>
        <el-button text @click="retryCsrf">重試初始化</el-button>
      </div>

      <div class="identity-actions">
        <el-button
          type="danger"
          plain
          :loading="auth.state.action === 'logout'"
          :disabled="auth.state.action !== null"
          @click="logout"
        >
          退出登入
        </el-button>
      </div>
      <p class="identity-footnote">到期時間僅供提示，不會延長或取代伺服器授權判斷。</p>
    </section>
  </main>
</template>
