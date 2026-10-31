<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { AdminBookingApi } from "../bookings/api";
import { requireContentApi } from "../content/inject";
import { AdminOutboxApi } from "../outbox/api";
import { authStoreKey } from "../auth/store";

const auth = inject(authStoreKey);
const permissions = computed(() => auth?.state.identity?.permissions ?? []);
const canReadContent = computed(() => permissions.value.includes("CONTENT_READ"));
const canReadBookings = computed(() => permissions.value.includes("RESERVATION_READ"));
const canReadOutbox = computed(() => permissions.value.includes("OUTBOX_READ"));

const pendingCount = ref<number | null>(null);
const failureCount = ref<number | null>(null);
const catalogRevision = ref<number | null>(null);

onMounted(async () => {
  if (canReadBookings.value) {
    try {
      pendingCount.value = (await new AdminBookingApi().pending()).length;
    } catch {
      pendingCount.value = null;
    }
  }
  if (canReadOutbox.value) {
    try {
      failureCount.value = (await new AdminOutboxApi().list()).failures.length;
    } catch {
      failureCount.value = null;
    }
  }
  if (canReadContent.value) {
    try {
      catalogRevision.value = (await requireContentApi().getCatalog()).revision;
    } catch {
      catalogRevision.value = null;
    }
  }
});
</script>

<template>
  <main class="workbench">
    <header class="workbench-top">
      <div>
        <p class="eyebrow">今天</p>
        <h1 id="identity-title">今天先看这些。</h1>
      </div>
      <p class="pill">本地工作区 · 合成资料</p>
    </header>

    <section class="workbench-grid">
      <article class="warm-card">
        <h2>要处理的事</h2>
        <router-link
          v-if="canReadBookings"
          class="work-row"
          to="/bookings"
          data-testid="bookings-link"
        >
          <span class="work-count">{{ pendingCount ?? "—" }}</span>
          <span>
            <strong>待确认预约</strong>
            <small>还没占档。确认前先核对时段。</small>
          </span>
        </router-link>
        <router-link
          v-if="canReadContent"
          class="work-row"
          to="/catalog"
          data-testid="catalog-link"
        >
          <span class="work-count">{{ catalogRevision ?? "—" }}</span>
          <span>
            <strong>品牌、图片与价格</strong>
            <small>文字、封面、套餐和联系方式都从目录发布。数字是当前草稿修订。</small>
          </span>
        </router-link>
        <router-link
          v-if="canReadContent"
          class="work-row"
          to="/works"
          data-testid="works-link"
        >
          <span class="work-count">图</span>
          <span>
            <strong>作品与图片</strong>
            <small>上传、授权、发布和撤回仍在作品页，不另造一套记录。</small>
          </span>
        </router-link>
        <router-link v-if="canReadOutbox" class="work-row" to="/outbox" data-testid="outbox-link">
          <span class="work-count">{{ failureCount ?? "—" }}</span>
          <span>
            <strong>通知停在待办</strong>
            <small>失败可见，可以人工再试。不代表客户已经读到。</small>
          </span>
        </router-link>
        <p v-if="!canReadContent && !canReadBookings && !canReadOutbox" class="panel-copy">
          当前账号还没有可配置的内容能力。每个请求仍由服务器重新授权。
        </p>
      </article>
      <article class="warm-card warm-note">
        <h2>客人会看到什么</h2>
        <p>小程序上的名称、介绍、图片和价格，来自已发布的目录和作品。</p>
        <p>没确认的价格保持「未披露」。保存草稿不会改变公开页。</p>
        <p>本阶段不收款，页面上不会出现支付按钮。</p>
      </article>
    </section>
  </main>
</template>
