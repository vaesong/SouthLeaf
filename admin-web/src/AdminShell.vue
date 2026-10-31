<script setup lang="ts">
import { computed, inject, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { AdminApiError } from "./auth/api";
import { authStoreKey } from "./auth/store";

const auth = inject(authStoreKey);
const route = useRoute();
const router = useRouter();
const notice = ref("");

const permissions = computed(() => auth?.state.identity?.permissions ?? []);
const accountId = computed(() => auth?.state.identity?.accountId ?? "");

const links = computed(() =>
  [
    { to: "/", label: "今日", show: true },
    { to: "/bookings", label: "预约", show: permissions.value.includes("RESERVATION_READ") },
    { to: "/works", label: "作品与图片", show: permissions.value.includes("CONTENT_READ") },
    { to: "/catalog", label: "品牌与套餐", show: permissions.value.includes("CONTENT_READ") },
    { to: "/outbox", label: "通知", show: permissions.value.includes("OUTBOX_READ") },
  ].filter((link) => link.show),
);

function current(path: string) {
  return path === "/" ? route.path === "/" : route.path.startsWith(path);
}

async function logout() {
  notice.value = "";
  try {
    await auth?.logout();
    await router.replace({ name: "login" });
  } catch (error) {
    const failure = error instanceof AdminApiError ? error : null;
    notice.value = failure?.message || "服务器端退出尚未确认。请重试；当前工作阶段仍视为有效。";
  }
}
</script>

<template>
  <div class="admin-shell">
    <aside class="admin-side">
      <RouterLink class="admin-mark" to="/" aria-label="南叶管理首页">
        <span class="admin-leaf">叶</span>
        <span><strong>南叶</strong><small>工作室后台</small></span>
      </RouterLink>
      <nav class="admin-nav" aria-label="后台分区">
        <RouterLink
          v-for="link in links"
          :key="link.to"
          :to="link.to"
          :aria-current="current(link.to) ? 'page' : undefined"
        >
          {{ link.label }}
        </RouterLink>
      </nav>
      <div class="admin-side-foot">
        <p>{{ accountId }}</p>
        <button type="button" :disabled="auth?.state.action !== null" @click="logout">
          {{ auth?.state.action === "logout" ? "正在退出…" : "退出登录" }}
        </button>
        <p v-if="notice" role="alert">{{ notice }}</p>
        <p>草稿不会出现在小程序。本阶段不收款。</p>
      </div>
    </aside>
    <div class="admin-main">
      <slot />
    </div>
  </div>
</template>
