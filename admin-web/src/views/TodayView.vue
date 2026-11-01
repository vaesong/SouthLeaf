<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { authStoreKey } from "../auth/store";
import { AdminBookingApi, type OpenSlot, type ReservationSummary } from "../bookings/api";
import { AdminOutboxApi } from "../outbox/api";
import { catalogStoreKey } from "../content/catalogStore";
import {
  beijingParts,
  formatClock,
  formatDateTime,
  formatRelative,
  formatTodayLabel,
  greeting,
} from "../content/format";

const auth = inject(authStoreKey);
const catalog = inject(catalogStoreKey);
const permissions = computed(() => auth?.state.identity?.permissions ?? []);
const canReadContent = computed(() => permissions.value.includes("CONTENT_READ"));
const canReadBookings = computed(() => permissions.value.includes("RESERVATION_READ"));
const canReadOrders = computed(() => permissions.value.includes("ORDER_READ"));
const canReadOutbox = computed(() => permissions.value.includes("OUTBOX_READ"));

const pending = ref<ReservationSummary[]>([]);
const slots = ref<OpenSlot[]>([]);
const rows = ref<Array<{ id: string; title: string; when: string; expire: string }>>([]);
const failureCount = ref<number | null>(null);
const waitingCount = ref<number | null>(null);
const loadError = ref("");

const todaySlots = computed(() => {
  const today = beijingParts(new Date());
  return slots.value.filter((slot) => {
    const at = beijingParts(slot.startAt);
    return (
      !!today && !!at && at.year === today.year && at.month === today.month && at.day === today.day
    );
  });
});

const missingAltCount = computed(
  () =>
    Object.values(catalog?.state.draft?.mediaAltTexts ?? {}).filter((text) => !text.trim()).length,
);
const unpublishedBusinesses = computed(
  () => catalog?.state.draft?.businesses.filter((item) => !item.published).length ?? 0,
);

onMounted(async () => {
  if (canReadContent.value) void catalog?.load();
  if (canReadBookings.value) {
    const api = new AdminBookingApi();
    try {
      pending.value = await api.pending();
    } catch {
      loadError.value = "待确认预约暂时读不出来。";
    }
    const from = new Date();
    const to = new Date(from.getTime() + 60 * 24 * 60 * 60 * 1000);
    try {
      slots.value = await api.openSlots(from.toISOString(), to.toISOString());
    } catch {
      slots.value = [];
    }
    rows.value = [];
    for (const item of pending.value) {
      let title = "新的预约请求";
      let when = "时间待定";
      try {
        const detail = await api.reservation(item.id);
        if (detail.data.orderId && canReadOrders.value) {
          const order = await api.order(detail.data.orderId);
          if (order.snapshot.packageDisplayName) title = order.snapshot.packageDisplayName;
          if (order.snapshot.startAt) when = formatDateTime(order.snapshot.startAt);
        } else if (item.slotId) {
          const slot = slots.value.find((entry) => entry.id === item.slotId);
          if (slot) when = formatDateTime(slot.startAt);
        }
      } catch {
        when = "时间待定";
      }
      rows.value.push({
        id: item.id,
        title,
        when,
        expire: formatRelative(item.expiresAt),
      });
    }
  }
  if (canReadOutbox.value) {
    try {
      const board = await new AdminOutboxApi().list();
      failureCount.value = board.failures.length;
      waitingCount.value = board.pendingCount;
    } catch {
      failureCount.value = null;
    }
  }
});
</script>

<template>
  <div>
    <div class="hero-greet">
      <div class="greet">
        <small>{{ formatTodayLabel() }}</small>
        <h1>{{ greeting() }}</h1>
        <p v-if="canReadBookings">今天有 {{ pending.length }} 位客人在等你回复。</p>
        <p v-else>从左边进入可以查看的内容。</p>
        <div class="row">
          <RouterLink v-if="canReadBookings" class="btn btn-light" to="/bookings"
            >去处理预约</RouterLink
          >
          <RouterLink v-if="canReadContent" class="btn btn-secondary" to="/home-layout"
            >布置首页</RouterLink
          >
        </div>
      </div>
      <div class="stats">
        <RouterLink v-if="canReadBookings" class="card stat alert" to="/bookings">
          <small>待确认预约</small><b>{{ pending.length }}</b
          ><span>位客人在等回复</span>
        </RouterLink>
        <RouterLink v-if="canReadBookings" class="card stat" to="/slots">
          <small>今天可约</small><b>{{ todaySlots.length }}</b>
          <span>{{
            todaySlots.map((slot) => formatClock(slot.startAt)).join(" · ") || "今天没有可约时段"
          }}</span>
        </RouterLink>
        <RouterLink v-if="canReadContent" class="card stat" to="/media">
          <small>图片缺描述</small><b>{{ missingAltCount }}</b>
          <span>张，未上架业务 {{ unpublishedBusinesses }} 个</span>
        </RouterLink>
        <RouterLink v-if="canReadOutbox" class="card stat" to="/notifications">
          <small>消息没发出去</small><b>{{ failureCount ?? "—" }}</b>
          <span>还有 {{ waitingCount ?? "—" }} 条在等待发送</span>
        </RouterLink>
      </div>
    </div>
    <p v-if="loadError" class="alert-line" role="alert">{{ loadError }}</p>
    <div class="two-col">
      <div class="card">
        <div class="card-head">
          <h2>待处理</h2>
          <p>先回复这些客人</p>
        </div>
        <p v-if="!canReadBookings" class="card-pad">当前账号不能查看预约。</p>
        <p v-else-if="rows.length === 0" class="card-pad">今天没有客人在等回复。</p>
        <div v-for="item in rows" :key="item.id" class="todo">
          <div>
            <b>{{ item.title }}</b>
            <small>{{ item.when }} · {{ item.expire }}</small>
          </div>
          <RouterLink class="btn btn-primary btn-sm" to="/bookings">去回复</RouterLink>
        </div>
      </div>
      <div class="card">
        <div class="card-head">
          <h2>今天的拍摄</h2>
          <div class="right">
            <RouterLink class="btn btn-ghost btn-sm" to="/slots">看整周</RouterLink>
          </div>
        </div>
        <div class="timeline">
          <p v-if="todaySlots.length === 0" class="card-pad">今天还没有可约的时段。</p>
          <div v-for="slot in todaySlots" :key="slot.id" class="tl">
            <time>{{ formatClock(slot.startAt) }}</time>
            <div>
              <b>{{ formatClock(slot.startAt) }} – {{ formatClock(slot.endAt) }}</b>
              <small>可预约</small>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
