<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
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
    notice.value = "操作结果尚未确认。请先重新加载预约核对；重试将沿用同一幂等命令。";
  } else if (failure?.status === 409) {
    notice.value = "预约版本或时段已变更。请重新加载资料，核对后再操作。";
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
    notice.value = "请输入预约 ID。";
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
    "请核对后确认（此动作会占用时段）：",
    "",
    `预约 ${reservation.id}`,
    `状态 ${reservation.status}`,
    `目前修订 ${reservation.version}`,
    `来源 ${reservation.source}`,
    `失效时间 ${reservation.expiresAt}`,
    "",
    `将要占用的时段：${slot.startAt} — ${slot.endAt}`,
    `业务时区 ${slot.businessZone} ${slot.displayOffset}`,
    "",
    "确认后于同一交易内占用时段、建立唯一订单与不可变快照；不产生支付或定金。",
  ].join("\n");
  if (!window.confirm(reviewed)) return;
  await runAction("confirm", confirmSlot.value);
}

async function rejectReservation() {
  if (!detail.value || !canReject.value) return;
  const reservation = detail.value.reservation;
  const reviewed = [
    "请核对后拒绝此申请（此动作不占用时段、不建立订单）：",
    "",
    `预约 ${reservation.id}`,
    `状态 ${reservation.status}`,
    `目前修订 ${reservation.version}`,
    `来源 ${reservation.source}`,
    `失效时间 ${reservation.expiresAt}`,
    "",
    "拒绝只关闭申请并留下审计与通知；不会占用档期，也不会产生订单与快照。",
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
  if (
    !window.confirm(
      "确认取消此预约？取消会释放已占用时段并新增订单修订；本地流程不处理退款或支付。",
    )
  )
    return;
  await runAction("cancel");
}

async function reschedule() {
  if (!detail.value || !canReschedule.value || !selectedSlot.value) return;
  const slot = slots.value.find((candidate) => candidate.id === selectedSlot.value);
  if (!slot) return;
  if (!window.confirm(`确认改期至 ${slot.startAt}（${slot.businessZone}）？`)) return;
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

function selectReservation(id: string) {
  lookupId.value = id;
  void lookup(id);
}

onMounted(() => void refreshPending());
</script>

<template>
  <main class="identity-shell booking-shell">
    <header class="topbar">
      <RouterLink class="wordmark" to="/" aria-label="SouthLeaf 管理首页">
        <span class="wordmark-mark">S</span>
        <span>SouthLeaf <small>ADMIN</small></span>
      </RouterLink>
      <div class="topbar-status">
        <span class="environment-badge">S6 · 本地预约工作台</span>
        <span class="secure-label"><span class="secure-dot"></span>安全工作阶段</span>
      </div>
    </header>

    <section class="booking-card" aria-labelledby="booking-title">
      <div class="booking-heading">
        <div>
          <p class="eyebrow">RESERVATION OPERATIONS</p>
          <h1 id="booking-title">预约与订单快照</h1>
          <p class="panel-copy">
            只显示预约与不可变更的订单快照；不显示客户标识、姓名或联络资料。取消与改期均保留审计、修订及通知事实。
          </p>
        </div>
        <button
          class="secondary-button"
          type="button"
          :disabled="loading"
          @click="refreshPending()"
        >
          重新整理待确认清单
        </button>
      </div>

      <div class="local-banner" role="status">
        <strong>本地 demo-only</strong>
        <span>不会调用真实通知 provider；外送 exactly-once 不在保证范围内。</span>
      </div>

      <div v-if="notice" class="booking-notice" role="alert" aria-live="assertive">
        <span>{{ notice }}</span>
        <small v-if="requestId">Request ID: {{ requestId }}</small>
      </div>

      <div v-if="!canRead" class="empty-state" role="status">
        当前会话缺少预约查阅能力；服务器仍会逐请求检查权限。
      </div>
      <template v-else>
        <section class="booking-section" aria-labelledby="lookup-title">
          <div class="section-title-row">
            <h2 id="lookup-title">依预约 ID 查阅</h2>
            <span>不以客户资料搜寻</span>
          </div>
          <form class="lookup-form" @submit.prevent="lookup()">
            <label for="reservation-id">预约 ID</label>
            <input
              id="reservation-id"
              v-model="lookupId"
              name="reservationId"
              autocomplete="off"
              maxlength="36"
              placeholder="例如：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
            />
            <button class="primary-button" type="submit" :disabled="loading || !lookupId.trim()">
              {{ loading ? "读取中…" : "查阅预约" }}
            </button>
          </form>
        </section>

        <section class="booking-section" aria-labelledby="pending-title">
          <div class="section-title-row">
            <h2 id="pending-title">待确认预约</h2>
            <span>{{ pending.length }} 笔（最多 100 笔）</span>
          </div>
          <div v-if="pending.length === 0" class="empty-state" role="status">
            目前没有待确认预约。已确认预约可使用上方 ID 查阅。
          </div>
          <div v-else class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">预约 ID</th>
                  <th scope="col">状态</th>
                  <th scope="col">建立时间</th>
                  <th scope="col">到期时间</th>
                  <th scope="col">版本</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="reservation in pending" :key="reservation.id">
                  <td>
                    <button
                      class="id-link"
                      type="button"
                      @click="selectReservation(reservation.id)"
                    >
                      {{ reservation.id }}
                    </button>
                  </td>
                  <td>{{ reservation.status }}</td>
                  <td>
                    <time :datetime="reservation.createdAt">{{ reservation.createdAt }}</time>
                  </td>
                  <td>
                    <time :datetime="reservation.expiresAt">{{ reservation.expiresAt }}</time>
                  </td>
                  <td>{{ reservation.version }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section
          v-if="detail"
          class="booking-section detail-section"
          aria-labelledby="detail-title"
        >
          <div class="section-title-row">
            <h2 id="detail-title">预约详情</h2>
            <span>修订 {{ detail.reservation.version }}</span>
          </div>
          <dl class="booking-details">
            <div>
              <dt>预约 ID</dt>
              <dd>
                <code>{{ detail.reservation.id }}</code>
              </dd>
            </div>
            <div>
              <dt>状态</dt>
              <dd>{{ detail.reservation.status }}</dd>
            </div>
            <div>
              <dt>来源</dt>
              <dd>{{ detail.reservation.source }}</dd>
            </div>
            <div>
              <dt>目前时段</dt>
              <dd>{{ detail.reservation.slotId ?? "尚未占用" }}</dd>
            </div>
            <div>
              <dt>建立时间</dt>
              <dd>{{ detail.reservation.createdAt }}</dd>
            </div>
            <div>
              <dt>订单 ID</dt>
              <dd>{{ detail.orderId ?? "尚无订单" }}</dd>
            </div>
          </dl>

          <div v-if="detail.assignments.length" class="history-block">
            <h3>时段指派历史</h3>
            <ol>
              <li
                v-for="assignment in detail.assignments"
                :key="`${assignment.revision}:${assignment.assignedAt}`"
              >
                <code>{{ assignment.previousSlotId ?? "初次指派" }} → {{ assignment.slotId }}</code>
                <span
                  >{{ assignment.assignedAt }} · {{ assignment.reasonCode }} · 修订
                  {{ assignment.revision }}</span
                >
              </li>
            </ol>
          </div>

          <div
            v-if="detail.reservation.status === 'PENDING_CONFIRMATION'"
            class="booking-actions confirm-block"
          >
            <div v-if="canConfirm" class="reschedule-control">
              <button
                class="secondary-button"
                type="button"
                :disabled="busy || loadingConfirmSlots"
                @click="loadConfirmSlots"
              >
                {{ loadingConfirmSlots ? "查询时段中…" : "查询可占用时段" }}
              </button>
              <label v-if="confirmSlots.length" for="confirm-slot">要占用的时段</label>
              <select
                v-if="confirmSlots.length"
                id="confirm-slot"
                v-model="confirmSlot"
                :disabled="busy"
              >
                <option v-for="slot in confirmSlots" :key="slot.id" :value="slot.id">
                  {{ slot.startAt }} — {{ slot.endAt }} · {{ slot.businessZone }}
                  {{ slot.displayOffset }}
                </option>
              </select>
              <button
                v-if="confirmSlots.length"
                class="primary-button"
                type="button"
                :disabled="busy || !confirmSlot"
                @click="confirmAndCreateOrder"
              >
                {{ busy ? "处理中…" : "核对并确认成单" }}
              </button>
            </div>
            <p v-else class="muted">此会话没有确认成单能力。</p>
            <button
              v-if="canReject"
              class="danger-button"
              type="button"
              :disabled="busy"
              @click="rejectReservation"
            >
              {{ busy ? "处理中…" : "拒绝申请" }}
            </button>
            <p class="muted">
              待确认申请不占档期。确认前请核对修订与所选时段；确认于同一交易内占用时段、建立唯一订单与不可变快照，且不产生支付或定金。
            </p>
          </div>

          <div v-if="detail.reservation.status === 'CONFIRMED'" class="booking-actions">
            <button
              v-if="canCancel"
              class="danger-button"
              type="button"
              :disabled="busy"
              @click="cancel"
            >
              {{ busy ? "处理中…" : "取消预约" }}
            </button>
            <div v-if="canReschedule" class="reschedule-control">
              <button
                class="secondary-button"
                type="button"
                :disabled="busy || loadingSlots"
                @click="loadSlots"
              >
                {{ loadingSlots ? "查询时段中…" : "查询可用改期时段" }}
              </button>
              <label v-if="slots.length" for="new-slot">新时段</label>
              <select v-if="slots.length" id="new-slot" v-model="selectedSlot" :disabled="busy">
                <option v-for="slot in slots" :key="slot.id" :value="slot.id">
                  {{ slot.startAt }} — {{ slot.endAt }} · {{ slot.businessZone }}
                  {{ slot.displayOffset }}
                </option>
              </select>
              <button
                v-if="slots.length"
                class="primary-button"
                type="button"
                :disabled="busy || !selectedSlot"
                @click="reschedule"
              >
                {{ busy ? "处理中…" : "确认改期" }}
              </button>
            </div>
            <span v-if="!canCancel && !canReschedule" class="muted"
              >此会话没有取消或改期能力。</span
            >
          </div>
        </section>

        <section
          v-if="order && canReadOrders"
          class="booking-section order-section"
          aria-labelledby="order-title"
        >
          <div class="section-title-row">
            <h2 id="order-title">订单快照与修订</h2>
            <span>{{ order.lifecycle }} · 修订 {{ order.currentRevision }}</span>
          </div>
          <dl class="booking-details">
            <div>
              <dt>订单 ID</dt>
              <dd>
                <code>{{ order.id }}</code>
              </dd>
            </div>
            <div>
              <dt>套餐快照</dt>
              <dd>{{ order.snapshot.packageDisplayName }}</dd>
            </div>
            <div>
              <dt>价格快照</dt>
              <dd>
                {{ order.snapshot.amountMinorUnits }} {{ order.snapshot.currencyCode }} 最小货币单位
                · {{ order.snapshot.priceVersion }}
              </dd>
            </div>
            <div>
              <dt>原始时段</dt>
              <dd>{{ order.snapshot.startAt }} — {{ order.snapshot.endAt }}</dd>
            </div>
            <div>
              <dt>业务时区</dt>
              <dd>{{ order.snapshot.businessZone }} {{ order.snapshot.displayOffset }}</dd>
            </div>
            <div>
              <dt>快照建立时间</dt>
              <dd>{{ order.snapshot.capturedAt }}</dd>
            </div>
          </dl>
          <ol v-if="order.revisions.length" class="revision-list">
            <li v-for="revision in order.revisions" :key="revision.revision">
              <strong>修订 {{ revision.revision }}</strong>
              <code>{{ revision.changedFields }}</code>
              <span>{{ revision.reasonCode }} · {{ revision.createdAt }}</span>
            </li>
          </ol>
        </section>
      </template>

      <p class="booking-footnote">
        改期只在新时段可用且版本匹配时提交；取消会释放占档并追加订单修订。失联后先查阅同一预约再重试同一命令。此工具不建立／确认新预约，也不处理退款、支付或生产通知。
      </p>
      <RouterLink class="back-link" to="/">返回首页</RouterLink>
    </section>
  </main>
</template>

<style scoped>
.booking-shell {
  min-height: 100vh;
  padding-bottom: 48px;
}
.booking-card {
  width: min(1180px, calc(100% - 32px));
  margin: 42px auto;
  padding: 32px;
  border: 1px solid #e7e5df;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 20px 50px #24352b12;
}
.booking-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
}
h1 {
  margin: 4px 0 10px;
  color: #21352c;
  font-size: clamp(28px, 4vw, 38px);
  letter-spacing: -0.04em;
}
.panel-copy {
  max-width: 760px;
  color: #647068;
  line-height: 1.65;
}
.local-banner {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin: 20px 0;
  padding: 14px 18px;
  border: 1px solid #d6c995;
  border-radius: 12px;
  background: #fff9e8;
  color: #5a4a1c;
}
.booking-notice {
  display: grid;
  gap: 6px;
  margin: 18px 0;
  padding: 14px 16px;
  border-left: 4px solid #bd7742;
  background: #fff4ed;
  color: #673b27;
}
.booking-notice small {
  color: #755e51;
}
.booking-section {
  margin: 26px 0;
  padding-top: 20px;
  border-top: 1px solid #eceee9;
}
.section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 14px;
}
h2 {
  margin: 0;
  color: #294738;
  font-size: 20px;
}
.section-title-row > span {
  color: #758077;
  font-size: 13px;
}
.lookup-form {
  display: grid;
  grid-template-columns: auto minmax(180px, 1fr) auto;
  align-items: center;
  gap: 12px;
}
.lookup-form label,
.reschedule-control label {
  color: #55635a;
  font-weight: 700;
}
input,
select {
  min-height: 42px;
  padding: 9px 12px;
  border: 1px solid #d5dad4;
  border-radius: 8px;
  background: #fff;
  color: #27372e;
  font: inherit;
}
.primary-button,
.secondary-button,
.danger-button {
  min-height: 40px;
  padding: 9px 14px;
  border: 1px solid #355647;
  border-radius: 9px;
  background: #fff;
  color: #294738;
  font-weight: 700;
  cursor: pointer;
}
.primary-button {
  background: #315a45;
  color: #fff;
}
.danger-button {
  border-color: #9a5140;
  color: #8a3d30;
}
button:disabled {
  opacity: 0.55;
  cursor: wait;
}
.table-wrap {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
}
th,
td {
  padding: 13px 10px;
  border-bottom: 1px solid #eceee9;
  vertical-align: middle;
}
th {
  color: #55635a;
  font-size: 13px;
  font-weight: 700;
}
td {
  color: #27372e;
  font-size: 14px;
}
.id-link {
  padding: 0;
  border: 0;
  background: none;
  color: #32644b;
  font: inherit;
  cursor: pointer;
  text-decoration: underline;
}
code {
  overflow-wrap: anywhere;
  color: #48564c;
  font-size: 12px;
}
.empty-state {
  margin: 16px 0;
  padding: 26px;
  border-radius: 12px;
  background: #f6f7f3;
  color: #647068;
  text-align: center;
}
.booking-details {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 20px;
  margin: 16px 0;
}
.booking-details div {
  min-width: 0;
}
.booking-details dt {
  color: #78827a;
  font-size: 12px;
}
.booking-details dd {
  margin: 4px 0 0;
  color: #27372e;
  overflow-wrap: anywhere;
}
.history-block {
  margin: 22px 0;
}
h3 {
  margin: 0 0 10px;
  color: #41594a;
  font-size: 16px;
}
.history-block ol,
.revision-list {
  display: grid;
  gap: 10px;
  padding-left: 22px;
}
.history-block li,
.revision-list li {
  display: grid;
  gap: 4px;
  color: #55635a;
  font-size: 13px;
}
.booking-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 14px;
  margin: 22px 0;
}
.reschedule-control {
  display: grid;
  grid-template-columns: auto minmax(200px, 1fr) auto;
  align-items: center;
  gap: 10px;
  flex: 1 1 600px;
}
.muted {
  color: #8b938d;
  font-size: 13px;
}
.booking-footnote {
  margin: 26px 0 16px;
  color: #6a756d;
  font-size: 13px;
  line-height: 1.7;
}
.back-link {
  color: #32644b;
  text-decoration: none;
  font-weight: 700;
}
@media (max-width: 760px) {
  .booking-card {
    padding: 20px;
  }
  .booking-heading {
    flex-direction: column;
  }
  .lookup-form {
    grid-template-columns: 1fr;
  }
  .booking-details {
    grid-template-columns: 1fr;
  }
  .reschedule-control {
    grid-template-columns: 1fr;
  }
  th,
  td {
    padding: 12px 8px;
  }
}
</style>
