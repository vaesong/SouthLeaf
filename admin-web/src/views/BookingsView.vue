<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { authStoreKey } from "../auth/store";
import {
  AdminBookingApi,
  BookingApiError,
  newBookingIdempotencyKey,
  type BookingAction,
  type OpenSlot,
  type OrderDetail,
  type ReservationDetail,
  type ReservationSummary,
} from "../bookings/api";
import {
  beijingParts,
  formatClock,
  formatDateTime,
  formatRelative,
  reservationSourceLabel,
  reservationStatusLabel,
  tailNumber,
} from "../content/format";

const api = new AdminBookingApi();
const auth = inject(authStoreKey);
const canRead = computed(
  () => auth?.state.identity?.permissions.includes("RESERVATION_READ") ?? false,
);
const canReadOrders = computed(
  () => auth?.state.identity?.permissions.includes("ORDER_READ") ?? false,
);
const canCancel = computed(
  () => auth?.state.identity?.permissions.includes("RESERVATION_CANCEL") ?? false,
);
const canReschedule = computed(
  () => auth?.state.identity?.permissions.includes("RESERVATION_RESCHEDULE") ?? false,
);
const canReject = computed(
  () => auth?.state.identity?.permissions.includes("RESERVATION_REJECT") ?? false,
);
const canConfirm = computed(
  () => auth?.state.identity?.permissions.includes("RESERVATION_CONFIRM") ?? false,
);
const pending = ref<ReservationSummary[]>([]);
const cards = ref<
  Array<{
    id: string;
    title: string;
    source: string;
    expiresAt: string;
    startAt: string | null;
  }>
>([]);
const detail = ref<ReservationDetail | null>(null);
const order = ref<OrderDetail | null>(null);
const slots = ref<OpenSlot[]>([]);
const lookupId = ref("");
const selectedSlot = ref("");
const confirmSlots = ref<OpenSlot[]>([]);
const confirmSlot = ref("");
const loadingConfirmSlots = ref(false);
const etag = ref("");
const loading = ref(false);
const loadingSlots = ref(false);
const busy = ref(false);
const notice = ref("");
const requestId = ref("");
const idempotency = new Map<string, string>();

function report(error: unknown) {
  const failure = error instanceof BookingApiError ? error : null;
  requestId.value = failure?.requestId ?? "";
  if (failure?.outcomeUnknown) {
    notice.value = "操作结果尚未确认。请先重新打开这单核对后再试。";
  } else if (failure?.status === 409) {
    notice.value = "这单预约或时段已经变了。请重新打开后再操作。";
  } else {
    notice.value = failure?.message ?? "暂时无法完成预约操作，请稍后重试。";
  }
}

async function refreshPending(preserveNotice = false) {
  if (!canRead.value) return;
  try {
    pending.value = await api.pending();
    await decorate(pending.value);
    if (!preserveNotice) {
      notice.value = "";
      requestId.value = "";
    }
  } catch (error) {
    report(error);
  }
}

async function lookup(id = lookupId.value, preserveNotice = false) {
  const normalized = id.trim();
  if (!normalized) {
    notice.value = "请输入预约编号。";
    return;
  }
  loading.value = true;
  if (!preserveNotice) {
    notice.value = "";
    requestId.value = "";
  }
  detail.value = null;
  order.value = null;
  slots.value = [];
  etag.value = "";
  lookupId.value = normalized;
  try {
    const result = await api.reservation(normalized);
    detail.value = result.data;
    etag.value = result.etag;
    if (result.data.orderId && canReadOrders.value) {
      try {
        order.value = await api.order(result.data.orderId);
      } catch (error) {
        report(error);
      }
    }
  } catch (error) {
    report(error);
  } finally {
    loading.value = false;
  }
}

async function loadSlots() {
  if (!detail.value || !canReschedule.value) return;
  loadingSlots.value = true;
  notice.value = "";
  const from = new Date().toISOString();
  const to = new Date(Date.now() + 60 * 24 * 60 * 60 * 1000).toISOString();
  try {
    slots.value = await api.openSlots(from, to);
    slots.value = slots.value.filter((slot) => slot.id !== detail.value?.reservation.slotId);
    selectedSlot.value = slots.value[0]?.id ?? "";
    if (slots.value.length === 0) notice.value = "目前查询范围内没有可改期时段。";
  } catch (error) {
    report(error);
  } finally {
    loadingSlots.value = false;
  }
}

async function loadConfirmSlots() {
  if (!detail.value || !canConfirm.value) return;
  loadingConfirmSlots.value = true;
  notice.value = "";
  const from = new Date().toISOString();
  const to = new Date(Date.now() + 60 * 24 * 60 * 60 * 1000).toISOString();
  try {
    confirmSlots.value = await api.openSlots(from, to);
    confirmSlot.value = confirmSlots.value[0]?.id ?? "";
    if (confirmSlots.value.length === 0) notice.value = "目前查询范围内没有可占用时段。";
  } catch (error) {
    report(error);
  } finally {
    loadingConfirmSlots.value = false;
  }
}

async function confirmAndCreateOrder() {
  if (!detail.value || !canConfirm.value || !confirmSlot.value) return;
  const slot = confirmSlots.value.find((candidate) => candidate.id === confirmSlot.value);
  if (!slot) return;
  const reservation = detail.value.reservation;
  const reviewed = [
    "请核对后确认。确认后会占住这个拍摄时段。",
    "",
    `预约编号 ${tailNumber(reservation.id)}`,
    `拍摄时间 ${formatDateTime(slot.startAt)}`,
    "本阶段不收款。",
  ].join("\n");
  if (!window.confirm(reviewed)) return;
  await runAction("confirm", confirmSlot.value);
}

async function rejectReservation() {
  if (!detail.value || !canReject.value) return;
  const reservation = detail.value.reservation;
  const reviewed = [
    "请核对后拒绝。婉拒后不会占住档期。",
    "",
    `预约编号 ${tailNumber(reservation.id)}`,
  ].join("\n");
  if (!window.confirm(reviewed)) return;
  await runAction("reject");
}

async function runAction(kind: "cancel" | "reschedule" | "confirm" | "reject", slotId?: string) {
  if (!detail.value || !etag.value || busy.value) return;
  const target = detail.value.reservation;
  const keyName = `${kind}:${target.id}`;
  const key = actionKey(keyName);
  busy.value = true;
  notice.value = "";
  try {
    // Cancel and reject close a request without naming a slot; confirm and reschedule must name one.
    if (!slotId && kind !== "cancel" && kind !== "reject") return;
    let result: BookingAction;
    if (kind === "cancel") {
      result = await api.cancel(target.id, etag.value, key);
    } else if (kind === "reject") {
      result = await api.reject(target.id, etag.value, key);
    } else if (kind === "reschedule") {
      result = await api.reschedule(target.id, slotId!, etag.value, key);
    } else {
      result = await api.confirm(target.id, slotId!, etag.value, key);
    }
    clearActionKey(keyName);
    const successNotice = result.replayed
      ? "已核对到同一笔操作记录；正在重新读取预约。"
      : kind === "cancel"
        ? "取消已记录；占用时段已依交易释放。没有退款或支付处理。"
        : kind === "reject"
          ? "拒绝已记录；申请已关闭，未占用任何时段、未建立订单。"
          : kind === "reschedule"
            ? "改期已记录；请核对新的时段及通知待办。"
            : "确认已记录；已建立唯一订单与不可变快照，请核对订单区块。";
    await lookup(target.id, true);
    await refreshPending(true);
    notice.value = successNotice;
  } catch (error) {
    if (error instanceof BookingApiError && error.outcomeUnknown) {
      await lookup(target.id, true);
      await refreshPending(true);
      const completed =
        kind === "cancel"
          ? detail.value?.reservation.status === "CANCELLED"
          : kind === "confirm"
            ? detail.value?.reservation.status === "CONFIRMED" && !!detail.value?.orderId
            : kind === "reject"
              ? detail.value?.reservation.status === "REJECTED"
              : !!slotId && detail.value?.reservation.slotId === slotId;
      if (completed) {
        clearActionKey(keyName);
        notice.value =
          kind === "cancel"
            ? "重新读取后已确认预约处于取消状态，占用时段已释放。"
            : kind === "reject"
              ? "重新读取后已确认申请已被拒绝。"
              : "重新读取后已确认预约目前位于所选时段。";
        requestId.value = "";
      } else {
        report(error);
      }
    } else {
      if (error instanceof BookingApiError && error.status === 409) {
        await lookup(target.id, true);
        await refreshPending(true);
        if (error.code !== "IDEMPOTENCY_KEY_REUSE") clearActionKey(keyName);
      } else if (error instanceof BookingApiError) {
        clearActionKey(keyName);
      }
      report(error);
    }
  } finally {
    busy.value = false;
  }
}

async function cancel() {
  if (!detail.value || !canCancel.value) return;
  if (!window.confirm("确认取消此预约？取消后这个时段会空出来。")) return;
  await runAction("cancel");
}

async function reschedule() {
  if (!detail.value || !canReschedule.value || !selectedSlot.value) return;
  const slot = slots.value.find((candidate) => candidate.id === selectedSlot.value);
  if (!slot) return;
  if (!window.confirm(`确认改期至 ${formatDateTime(slot.startAt)}？`)) return;
  await runAction("reschedule", selectedSlot.value);
}

function actionKey(name: string) {
  let key = idempotency.get(name);
  if (!key) {
    key = newBookingIdempotencyKey();
    idempotency.set(name, key);
  }
  return key;
}

function clearActionKey(name: string) {
  idempotency.delete(name);
}

async function decorate(list: ReservationSummary[]) {
  if (list.length === 0) {
    cards.value = [];
    return;
  }
  const from = new Date();
  const to = new Date(from.getTime() + 60 * 24 * 60 * 60 * 1000);
  if (slots.value.length === 0) {
    try {
      slots.value = await api.openSlots(from.toISOString(), to.toISOString());
    } catch {
      slots.value = [];
    }
  }
  const next = [];
  for (const item of list) {
    let title = "新的预约请求";
    let startAt: string | null = null;
    try {
      const loaded = await api.reservation(item.id);
      if (loaded.data.orderId && canReadOrders.value) {
        const snapshot = await api.order(loaded.data.orderId);
        if (snapshot.snapshot.packageDisplayName) title = snapshot.snapshot.packageDisplayName;
        startAt = snapshot.snapshot.startAt || null;
      } else if (item.slotId) {
        startAt = slots.value.find((slot) => slot.id === item.slotId)?.startAt ?? null;
      }
    } catch {
      startAt = null;
    }
    next.push({
      id: item.id,
      title,
      source: reservationSourceLabel(item.source),
      expiresAt: item.expiresAt,
      startAt,
    });
  }
  cards.value = next;
}

function shootParts(value: string | null) {
  if (!value) return null;
  return beijingParts(value);
}

function slotOption(slot: OpenSlot) {
  return `${formatDateTime(slot.startAt)} – ${formatClock(slot.endAt)}`;
}

const detailIsPending = computed(() => detail.value?.reservation.status === "PENDING_CONFIRMATION");
const detailIsConfirmed = computed(() => detail.value?.reservation.status === "CONFIRMED");

function slotTime(slotId: string | null) {
  if (!slotId) return "时间待定";
  const slot = slots.value.find((item) => item.id === slotId);
  return slot ? formatDateTime(slot.startAt) : "已安排";
}

function selectReservation(id: string) {
  lookupId.value = id;
  void lookup(id);
}

onMounted(() => void refreshPending());
</script>

<template>
  <div class="booking-card">
    <div class="page-head">
      <div>
        <h1>预约处理</h1>
        <p>先回复还在等你确认的客人。本阶段不收款。</p>
      </div>
      <div class="actions">
        <form class="search" @submit.prevent="lookup()">
          <input
            id="reservation-id"
            v-model="lookupId"
            name="reservationId"
            autocomplete="off"
            maxlength="36"
            placeholder="输入预约编号查找"
          />
          <button
            class="btn btn-secondary btn-sm"
            type="submit"
            :disabled="loading || !lookupId.trim()"
          >
            {{ loading ? "读取中…" : "查阅预约" }}
          </button>
        </form>
      </div>
    </div>
    <div class="tabs">
      <button class="tab on" type="button">
        待确认<span class="n">{{ pending.length }}</span>
      </button>
    </div>
    <p v-if="notice" class="alert-line" role="alert">{{ notice }}</p>
    <p v-if="!canRead">缺少预约查阅能力</p>
    <template v-else>
      <section class="card" style="margin-bottom: 18px">
        <div v-if="cards.length === 0" class="card-pad">目前没有待确认预约。</div>
        <div v-for="card in cards" :key="card.id" class="booking">
          <div class="date-box">
            <template v-if="shootParts(card.startAt)">
              <small>{{ shootParts(card.startAt)?.month }} 月</small>
              <b>{{ shootParts(card.startAt)?.day }}</b>
              <small
                >{{ shootParts(card.startAt)?.weekday }}
                {{ card.startAt ? formatClock(card.startAt) : "" }}</small
              >
            </template>
            <template v-else>
              <small>时间</small>
              <b style="font-size: 16px">待定</b>
            </template>
          </div>
          <div class="who">
            <b>{{ card.title }}</b>
            <small>{{ card.source }} · 预约编号 {{ tailNumber(card.id) }}</small>
          </div>
          <div class="expire">{{ formatRelative(card.expiresAt) }}</div>
          <div class="acts">
            <button
              class="btn btn-secondary btn-sm"
              type="button"
              @click="selectReservation(card.id)"
            >
              改期
            </button>
            <button
              class="btn btn-danger-ghost btn-sm"
              type="button"
              @click="selectReservation(card.id)"
            >
              婉拒
            </button>
            <button
              class="btn btn-primary btn-sm"
              type="button"
              @click="selectReservation(card.id)"
            >
              确认预约
            </button>
          </div>
        </div>
      </section>
      <section v-if="detail" class="card card-pad detail-section" style="margin-top: 18px">
        <h2>预约详情</h2>
        <dl>
          <div>
            <dt>预约编号</dt>
            <dd>{{ tailNumber(detail.reservation.id) }}</dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd>{{ reservationStatusLabel(detail.reservation.status) }}</dd>
          </div>
          <div>
            <dt>来源</dt>
            <dd>{{ reservationSourceLabel(detail.reservation.source) }}</dd>
          </div>
          <div>
            <dt>拍摄时间</dt>
            <dd>
              {{ slotTime(detail.reservation.slotId) }}
            </dd>
          </div>
          <div>
            <dt>提交时间</dt>
            <dd>{{ formatDateTime(detail.reservation.createdAt) }}</dd>
          </div>
        </dl>
        <div v-if="detail.assignments.length">
          <h3>改期记录</h3>
          <ol class="revision-list">
            <li v-for="assignment in detail.assignments" :key="assignment.assignedAt">
              <span>{{ formatDateTime(assignment.assignedAt) }} · 已调整拍摄时间</span>
            </li>
          </ol>
        </div>
        <div
          v-if="detailIsPending"
          class="confirm-block"
          style="display: flex; flex-wrap: wrap; gap: 10px; margin-top: 16px"
        >
          <template v-if="canConfirm">
            <button
              class="btn btn-secondary"
              type="button"
              :disabled="busy || loadingConfirmSlots"
              @click="loadConfirmSlots"
            >
              {{ loadingConfirmSlots ? "查询时段中…" : "查询可占用时段" }}
            </button>
            <select
              v-if="confirmSlots.length"
              id="confirm-slot"
              v-model="confirmSlot"
              class="select"
              :disabled="busy"
            >
              <option v-for="slot in confirmSlots" :key="slot.id" :value="slot.id">
                {{ slotOption(slot) }}
              </option>
            </select>
            <button
              v-if="confirmSlots.length"
              class="btn btn-primary"
              type="button"
              :disabled="busy || !confirmSlot"
              @click="confirmAndCreateOrder"
            >
              {{ busy ? "处理中…" : "核对并确认成单" }}
            </button>
          </template>
          <button
            v-if="canReject"
            class="btn btn-danger danger-button"
            type="button"
            :disabled="busy"
            @click="rejectReservation"
          >
            {{ busy ? "处理中…" : "拒绝申请" }}
          </button>
        </div>
        <div
          v-if="detailIsConfirmed"
          style="display: flex; flex-wrap: wrap; gap: 10px; margin-top: 16px"
        >
          <button
            v-if="canCancel"
            class="btn btn-danger danger-button"
            type="button"
            :disabled="busy"
            @click="cancel"
          >
            {{ busy ? "处理中…" : "取消预约" }}
          </button>
          <template v-if="canReschedule">
            <button
              class="btn btn-secondary"
              type="button"
              :disabled="busy || loadingSlots"
              @click="loadSlots"
            >
              {{ loadingSlots ? "查询时段中…" : "查询可用改期时段" }}
            </button>
            <select
              v-if="slots.length"
              id="new-slot"
              v-model="selectedSlot"
              class="select"
              :disabled="busy"
            >
              <option v-for="slot in slots" :key="slot.id" :value="slot.id">
                {{ slotOption(slot) }}
              </option>
            </select>
            <button
              v-if="slots.length"
              class="btn btn-primary"
              type="button"
              :disabled="busy || !selectedSlot"
              @click="reschedule"
            >
              {{ busy ? "处理中…" : "确认改期" }}
            </button>
          </template>
        </div>
      </section>
      <section
        v-if="order && canReadOrders"
        class="card card-pad order-section"
        style="margin-top: 18px"
      >
        <h2>预约内容</h2>
        <dl>
          <div>
            <dt>套餐</dt>
            <dd>{{ order.snapshot.packageDisplayName }}</dd>
          </div>
          <div>
            <dt>拍摄时间</dt>
            <dd>{{ formatDateTime(order.snapshot.startAt) }}</dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd>{{ reservationStatusLabel(order.lifecycle) }}</dd>
          </div>
        </dl>
        <ol v-if="order.revisions.length" class="revision-list">
          <li v-for="revision in order.revisions" :key="revision.createdAt">
            <span>{{ formatDateTime(revision.createdAt) }} · 已更新预约</span>
          </li>
        </ol>
      </section>
    </template>
  </div>
</template>
