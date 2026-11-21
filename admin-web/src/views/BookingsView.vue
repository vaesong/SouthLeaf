<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
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
import WeekBoard from "../components/WeekBoard.vue";
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
const route = useRoute();
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
  if (detail.value?.reservation.status === "PENDING_CONFIRMATION" && canConfirm.value) {
    await loadConfirmSlots();
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
    confirmSlot.value = "";
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

function slotOption(slot: OpenSlot) {
  const parts = beijingParts(slot.startAt);
  const range = `${formatClock(slot.startAt)}–${formatClock(slot.endAt)}`;
  if (!parts) return range;
  return `${parts.month}月${parts.day}日 ${parts.weekday} ${range}`;
}

function slotGroups(list: OpenSlot[]) {
  const groups: Array<{ label: string; slots: OpenSlot[] }> = [];
  for (const slot of list) {
    const parts = beijingParts(slot.startAt);
    const label = parts ? `${parts.month}月${parts.day}日 ${parts.weekday}` : "时间待定";
    const last = groups[groups.length - 1];
    if (last?.label === label) last.slots.push(slot);
    else groups.push({ label, slots: [slot] });
  }
  return groups;
}

const detailWhen = computed(() => {
  const start = order.value?.snapshot.startAt;
  if (start) return formatDateTime(start);
  if (!detail.value) return "";
  return slotTime(detail.value.reservation.slotId);
});

async function copyReservationId(id: string) {
  try {
    await navigator.clipboard.writeText(id);
    notice.value = "编号已复制，可以贴到查找框。";
  } catch {
    notice.value = "复制失败，请手动选择下面的编号。";
  }
}

const detailIsPending = computed(() => detail.value?.reservation.status === "PENDING_CONFIRMATION");
const detailIsConfirmed = computed(() => detail.value?.reservation.status === "CONFIRMED");

function slotTime(slotId: string | null) {
  if (!slotId) return "时间待定";
  const slot = slots.value.find((item) => item.id === slotId);
  return slot ? formatDateTime(slot.startAt) : "已安排";
}

onMounted(() => {
  void refreshPending();
  const reservation = route?.query?.reservation;
  if (typeof reservation === "string" && reservation.trim()) void lookup(reservation);
});
</script>

<template>
  <div class="booking-card">
    <div class="page-head">
      <div>
        <h1>预约处理</h1>
        <p>
          周历上是客人要的时间。点有申请的格子，就按那一档确认。查以前的单子时，贴上完整编号。本阶段不收款。
        </p>
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
    <p v-if="notice" class="alert-line" role="alert">{{ notice }}</p>
    <p v-if="!canRead">缺少预约查阅能力</p>
    <template v-else>
      <p class="hint">{{ pending.length }} 单还在等回复</p>
      <WeekBoard show-rules-link />
      <section v-if="detail" class="card detail-section" style="margin-top: 18px">
        <div class="card-head">
          <h2>{{ detailWhen || "时间待安排" }}</h2>
          <p>
            {{ reservationStatusLabel(detail.reservation.status) }} ·
            {{ reservationSourceLabel(detail.reservation.source) }}
          </p>
        </div>
        <div class="card-pad">
          <dl>
            <div>
              <dt>预约编号</dt>
              <dd>
                <span class="record-id">{{ detail.reservation.id }}</span>
                <button
                  class="btn btn-secondary btn-sm"
                  type="button"
                  @click="copyReservationId(detail.reservation.id)"
                >
                  复制编号
                </button>
              </dd>
            </div>
            <div>
              <dt>提交时间</dt>
              <dd>{{ formatDateTime(detail.reservation.createdAt) }}</dd>
            </div>
            <div>
              <dt>保留到</dt>
              <dd>
                {{ formatDateTime(detail.reservation.expiresAt) }} ·
                {{ formatRelative(detail.reservation.expiresAt) }}
              </dd>
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
              <p v-if="loadingConfirmSlots" class="hint">正在读取可预约时段…</p>
              <p class="hint">客人要的时间在周历上。这里是改到别的已开放档期，需要自己选中。</p>
              <select
                v-if="confirmSlots.length"
                id="confirm-slot"
                v-model="confirmSlot"
                class="select"
                :disabled="busy"
              >
                <option value="">选择要占的档期</option>
                <optgroup
                  v-for="group in slotGroups(confirmSlots)"
                  :key="group.label"
                  :label="group.label"
                >
                  <option v-for="slot in group.slots" :key="slot.id" :value="slot.id">
                    {{ slotOption(slot) }}
                  </option>
                </optgroup>
              </select>
              <button
                v-if="confirmSlots.length"
                class="btn btn-primary"
                type="button"
                :disabled="busy || !confirmSlot"
                @click="confirmAndCreateOrder"
              >
                {{ busy ? "处理中…" : "核对并确认" }}
              </button>
              <button
                class="btn btn-secondary"
                type="button"
                :disabled="busy || loadingConfirmSlots"
                @click="loadConfirmSlots"
              >
                重新读取时段
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
                <optgroup
                  v-for="group in slotGroups(slots)"
                  :key="group.label"
                  :label="group.label"
                >
                  <option v-for="slot in group.slots" :key="slot.id" :value="slot.id">
                    {{ slotOption(slot) }}
                  </option>
                </optgroup>
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
          <div>
            <dt>记录金额</dt>
            <dd>{{ order.snapshot.currencyCode }} {{ order.snapshot.amountMinorUnits }}</dd>
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
