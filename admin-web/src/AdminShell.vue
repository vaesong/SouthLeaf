<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { AdminApiError } from "./auth/api";
import { authStoreKey } from "./auth/store";
import { AdminBookingApi } from "./bookings/api";
import AppIcon from "./components/AppIcon.vue";
import ConfirmDialog from "./components/ConfirmDialog.vue";
import { mediaContentPath } from "./content/api";
import { catalogStoreKey } from "./content/catalogStore";
import { formatDateTime, shopReason } from "./content/format";

const auth = inject(authStoreKey);
const catalog = inject(catalogStoreKey);
const route = useRoute();
const router = useRouter();
const menuOpen = ref(false);
const logoutOpen = ref(false);
const publishOpen = ref(false);
const pendingCount = ref<number | null>(null);
const logoutError = ref("");
const publishMessage = ref("");
const publishAllowed = ref(false);

function formatExpiry(value: string) {
  if (!value) return "";
  const label = formatDateTime(value);
  return label === "时间待定" ? "" : `本次登录到期 ${label}`;
}

const permissions = computed(() => auth?.state.identity?.permissions ?? []);
const canReadContent = computed(() => permissions.value.includes("CONTENT_READ"));
const canEdit = computed(() => permissions.value.includes("CONTENT_EDIT"));
const canPublish = computed(() => permissions.value.includes("CONTENT_PUBLISH"));
const canReadBookings = computed(() => permissions.value.includes("RESERVATION_READ"));
const canReadOutbox = computed(() => permissions.value.includes("OUTBOX_READ"));
const expiresAt = computed(() => formatExpiry(auth?.state.identity?.expiresAt ?? ""));
const loadedBrand = computed(() => catalog?.state.draft?.brand.name?.trim() || "");
const brandName = computed(() => loadedBrand.value || "工作室后台");
const logoMediaId = computed(() => catalog?.state.draft?.brand.logoMediaId || null);
const brandMark = computed(() => loadedBrand.value.slice(0, 1) || "枝");
const title = computed(() => String(route.meta.title ?? "工作室后台"));
const contentRoute = computed(() =>
  ["about", "home-layout", "businesses", "releases", "works", "work-edit", "media"].includes(
    String(route.name),
  ),
);

type NavItem = {
  to: string;
  name: string;
  label: string;
  icon: string;
  show: boolean;
  testId?: string;
  count?: number | null;
};

const links = computed(
  (): Array<{ label: string; items: NavItem[] }> => [
    {
      label: "小程序内容",
      items: [
        {
          to: "/home-layout",
          name: "home-layout",
          label: "首页布置",
          icon: "monitor",
          show: canReadContent.value,
        },
        {
          to: "/about",
          name: "about",
          label: "关于我们",
          icon: "user",
          show: canReadContent.value,
        },
        {
          to: "/businesses",
          name: "businesses",
          label: "业务与套餐",
          icon: "briefcase",
          show: canReadContent.value,
        },
        {
          to: "/works",
          name: "works",
          label: "作品集",
          icon: "image",
          show: canReadContent.value,
          testId: "works-link",
        },
        {
          to: "/media",
          name: "media",
          label: "图片库",
          icon: "images",
          show: canReadContent.value,
        },
      ],
    },
    {
      label: "预约经营",
      items: [
        {
          to: "/bookings",
          name: "bookings",
          label: "预约处理",
          icon: "calendar",
          show: canReadBookings.value,
          count: pendingCount.value,
        },
        {
          to: "/slots",
          name: "slots",
          label: "档期安排",
          icon: "clock",
          show: canReadBookings.value,
        },
        {
          to: "/notifications",
          name: "notifications",
          label: "消息通知",
          icon: "bell",
          show: canReadOutbox.value,
        },
      ],
    },
    {
      label: "其他",
      items: [
        {
          to: "/releases",
          name: "releases",
          label: "发布记录",
          icon: "upload",
          show: canReadContent.value,
        },
      ],
    },
  ],
);

function current(name: string) {
  return route.name === name || (name === "works" && route.name === "work-edit");
}

async function askPublish() {
  if (!catalog) return;
  if (catalog.dirty) {
    catalog.state.notice = "有修改尚未保存，请先保存再发布。";
    return;
  }
  publishAllowed.value = false;
  publishMessage.value = "正在检查能否发布…";
  publishOpen.value = true;
  await catalog.previewDraft();
  const preview = catalog.state.preview;
  publishAllowed.value = !!preview?.publishable;
  publishMessage.value = preview?.publishable
    ? "检查通过，确认后会发布到小程序。"
    : shopReason(preview?.blockingReason);
}

async function confirmPublish() {
  publishOpen.value = false;
  await catalog?.publish();
}

async function reload() {
  await catalog?.load(true);
}

function askLogout() {
  menuOpen.value = false;
  logoutError.value = "";
  logoutOpen.value = true;
}

async function confirmLogout() {
  logoutError.value = "";
  try {
    await auth?.logout();
    logoutOpen.value = false;
    await router.replace({ name: "login" });
  } catch (error) {
    const failure = error instanceof AdminApiError ? error : null;
    logoutError.value = failure?.message || "退出还没有完成，请再试一次。";
  }
}

onMounted(async () => {
  if (canReadContent.value) void catalog?.load();
  if (canReadBookings.value) {
    try {
      pendingCount.value = (await new AdminBookingApi().pending()).length;
    } catch {
      pendingCount.value = null;
    }
  }
});
</script>

<template>
  <div class="app">
    <aside class="side">
      <RouterLink class="brand" to="/">
        <div class="brand-mark">
          <img v-if="logoMediaId" :src="mediaContentPath(logoMediaId, 'thumbnail')" alt="" />
          <template v-else>{{ brandMark }}</template>
        </div>
        <div>
          <b>{{ brandName }}</b>
          <small>工作室后台</small>
        </div>
      </RouterLink>
      <RouterLink class="nav-item" to="/" :class="{ active: route.name === 'today' }">
        <AppIcon name="home" />今日工作台
      </RouterLink>
      <div v-for="group in links" :key="group.label" class="nav-group">
        <p>{{ group.label }}</p>
        <RouterLink
          v-for="item in group.items.filter((entry) => entry.show)"
          :key="item.to"
          class="nav-item"
          :to="item.to"
          :class="{ active: current(item.name) }"
          :data-testid="item.testId"
        >
          <AppIcon :name="item.icon" />{{ item.label }}
          <span v-if="item.count" class="count">{{ item.count }}</span>
        </RouterLink>
      </div>
      <div class="side-foot"></div>
    </aside>
    <div class="main">
      <header class="topbar">
        <div class="crumb">
          {{ brandName }} / <b>{{ title }}</b>
        </div>
        <div class="top-actions">
          <span v-if="catalog?.unpublishedLabel" class="draft-pill"
            ><i></i>{{ catalog.unpublishedLabel }}</span
          >
          <span
            v-if="catalog?.state.revision !== null"
            data-testid="catalog-stored-revision"
            hidden
            :data-revision="catalog?.state.revision"
          ></span>
          <button
            class="btn btn-accent btn-sm"
            type="button"
            data-testid="catalog-publish"
            :disabled="!canPublish || catalog?.state.busy !== ''"
            @click="askPublish"
          >
            <AppIcon name="upload" />发布到小程序
          </button>
          <button
            v-if="catalog?.state.unknownAction"
            class="btn btn-secondary btn-sm"
            type="button"
            data-testid="catalog-resolve-unknown"
            :disabled="catalog?.state.busy !== ''"
            @click="catalog?.resolveUnknown()"
          >
            确认上次是否成功
          </button>
          <button
            v-if="catalog?.state.conflict"
            class="btn btn-secondary btn-sm"
            type="button"
            @click="reload"
          >
            重新加载
          </button>
          <div class="account">
            <button class="account-btn" type="button" @click="menuOpen = !menuOpen">
              <span class="avatar">管</span><span>管理员</span>
              <AppIcon name="chevron" />
            </button>
            <div class="menu" :class="{ open: menuOpen }">
              <div class="menu-head">
                <span class="avatar">管</span>
                <div>
                  <b>管理员</b>
                  <small v-if="expiresAt">{{ expiresAt }}</small>
                </div>
              </div>
              <div class="sep"></div>
              <button class="logout" type="button" @click="askLogout">
                <AppIcon name="logout" />退出登录
              </button>
            </div>
          </div>
        </div>
      </header>
      <div class="page">
        <p v-if="contentRoute && !canEdit" class="alert-line" role="alert">
          当前账号只能查看，不能修改。
        </p>
        <p
          v-if="catalog?.state.notice && contentRoute"
          class="notice-line"
          role="status"
          data-testid="catalog-notice"
        >
          {{ catalog.state.notice }}
        </p>
        <slot />
      </div>
    </div>
    <ConfirmDialog
      :open="logoutOpen"
      title="退出登录"
      message="确认结束这次登录？退出后需要重新登录才能继续。"
      confirm-text="确认退出"
      danger
      @cancel="logoutOpen = false"
      @confirm="confirmLogout"
    />
    <p v-if="logoutError" class="toast" role="alert">{{ logoutError }}</p>
    <ConfirmDialog
      :open="publishOpen"
      title="发布到小程序"
      :message="publishMessage"
      message-test-id="catalog-preview-status"
      confirm-text="确认发布"
      :confirm-disabled="!publishAllowed"
      @cancel="publishOpen = false"
      @confirm="confirmPublish"
    />
  </div>
</template>
