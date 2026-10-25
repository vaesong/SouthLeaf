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
    notice.value = "操作結果尚未確認。請先重新載入預約核對；重試將沿用同一冪等命令。";
  } else if (failure?.status === 409) {
    notice.value = "預約版本或時段已變更。請重新載入資料，核對後再操作。";
  } else {
    notice.value = failure?.message ?? "暫時無法完成預約操作，請稍後重試。";
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
    notice.value = "請輸入預約 ID。";
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
    if (slots.value.length === 0) notice.value = "目前查詢範圍內沒有可改期時段。";
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
    if (confirmSlots.value.length === 0) notice.value = "目前查詢範圍內沒有可佔用時段。";
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
    "請核對後確認（此動作會佔用時段）：",
    "",
    `預約 ${reservation.id}`,
    `狀態 ${reservation.status}`,
    `目前修訂 ${reservation.version}`,
    `來源 ${reservation.source}`,
    `失效時間 ${reservation.expiresAt}`,
    "",
    `將要佔用的時段：${slot.startAt} — ${slot.endAt}`,
    `業務時區 ${slot.businessZone} ${slot.displayOffset}`,
    "",
    "確認後於同一交易內佔用時段、建立唯一訂單與不可變快照；不產生支付或定金。",
  ].join("\n");
  if (!window.confirm(reviewed)) return;
  await runAction("confirm", confirmSlot.value);
}

async function rejectReservation() {
  if (!detail.value || !canReject.value) return;
  const reservation = detail.value.reservation;
  const reviewed = [
    "請核對後拒絕此申請（此動作不佔用時段、不建立訂單）：",
    "",
    `預約 ${reservation.id}`,
    `狀態 ${reservation.status}`,
    `目前修訂 ${reservation.version}`,
    `來源 ${reservation.source}`,
    `失效時間 ${reservation.expiresAt}`,
    "",
    "拒絕只關閉申請並留下審計與通知；不會佔用檔期，也不會產生訂單與快照。",
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
      ? "已核對到同一筆操作記錄；正在重新讀取預約。"
      : kind === "cancel"
        ? "取消已記錄；佔用時段已依交易釋放。沒有退款或支付處理。"
        : kind === "reject"
          ? "拒絕已記錄；申請已關閉，未佔用任何時段、未建立訂單。"
          : kind === "reschedule"
            ? "改期已記錄；請核對新的時段及通知待辦。"
            : "確認已記錄；已建立唯一訂單與不可變快照，請核對訂單區塊。";
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
            ? "重新讀取後已確認預約處於取消狀態，佔用時段已釋放。"
            : kind === "reject"
              ? "重新讀取後已確認申請已被拒絕。"
              : "重新讀取後已確認預約目前位於所選時段。";
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
      "確認取消此預約？取消會釋放已佔用時段並新增訂單修訂；本地流程不處理退款或支付。",
    )
  )
    return;
  await runAction("cancel");
}

async function reschedule() {
  if (!detail.value || !canReschedule.value || !selectedSlot.value) return;
  const slot = slots.value.find((candidate) => candidate.id === selectedSlot.value);
  if (!slot) return;
  if (!window.confirm(`確認改期至 ${slot.startAt}（${slot.businessZone}）？`)) return;
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
      <RouterLink class="wordmark" to="/" aria-label="SouthLeaf 管理首頁">
        <span class="wordmark-mark">S</span>
        <span>SouthLeaf <small>ADMIN</small></span>
      </RouterLink>
      <div class="topbar-status">
        <span class="environment-badge">S6 · 本地預約工作台</span>
        <span class="secure-label"><span class="secure-dot"></span>安全工作階段</span>
      </div>
    </header>

    <section class="booking-card" aria-labelledby="booking-title">
      <div class="booking-heading">
        <div>
          <p class="eyebrow">RESERVATION OPERATIONS</p>
          <h1 id="booking-title">預約與訂單快照</h1>
          <p class="panel-copy">
            只顯示預約與不可變更的訂單快照；不顯示客戶標識、姓名或聯絡資料。取消與改期均保留審計、修訂及通知事實。
          </p>
        </div>
        <button
          class="secondary-button"
          type="button"
          :disabled="loading"
          @click="refreshPending()"
        >
          重新整理待確認清單
        </button>
      </div>

      <div class="local-banner" role="status">
        <strong>本地 demo-only</strong>
        <span>不會呼叫真實通知 provider；外送 exactly-once 不在保證範圍內。</span>
      </div>

      <div v-if="notice" class="booking-notice" role="alert" aria-live="assertive">
        <span>{{ notice }}</span>
        <small v-if="requestId">Request ID: {{ requestId }}</small>
      </div>

      <div v-if="!canRead" class="empty-state" role="status">
        目前會話缺少預約查閱能力；伺服器仍會逐請求檢查權限。
      </div>
      <template v-else>
        <section class="booking-section" aria-labelledby="lookup-title">
          <div class="section-title-row">
            <h2 id="lookup-title">依預約 ID 查閱</h2>
            <span>不以客戶資料搜尋</span>
          </div>
          <form class="lookup-form" @submit.prevent="lookup()">
            <label for="reservation-id">預約 ID</label>
            <input
              id="reservation-id"
              v-model="lookupId"
              name="reservationId"
              autocomplete="off"
              maxlength="36"
              placeholder="例如：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
            />
            <button class="primary-button" type="submit" :disabled="loading || !lookupId.trim()">
              {{ loading ? "讀取中…" : "查閱預約" }}
            </button>
          </form>
        </section>

        <section class="booking-section" aria-labelledby="pending-title">
          <div class="section-title-row">
            <h2 id="pending-title">待確認預約</h2>
            <span>{{ pending.length }} 筆（最多 100 筆）</span>
          </div>
          <div v-if="pending.length === 0" class="empty-state" role="status">
            目前沒有待確認預約。已確認預約可使用上方 ID 查閱。
          </div>
          <div v-else class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">預約 ID</th>
                  <th scope="col">狀態</th>
                  <th scope="col">建立時間</th>
                  <th scope="col">到期時間</th>
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
            <h2 id="detail-title">預約詳情</h2>
            <span>修訂 {{ detail.reservation.version }}</span>
          </div>
          <dl class="booking-details">
            <div>
              <dt>預約 ID</dt>
              <dd>
                <code>{{ detail.reservation.id }}</code>
              </dd>
            </div>
            <div>
              <dt>狀態</dt>
              <dd>{{ detail.reservation.status }}</dd>
            </div>
            <div>
              <dt>來源</dt>
              <dd>{{ detail.reservation.source }}</dd>
            </div>
            <div>
              <dt>目前時段</dt>
              <dd>{{ detail.reservation.slotId ?? "尚未佔用" }}</dd>
            </div>
            <div>
              <dt>建立時間</dt>
              <dd>{{ detail.reservation.createdAt }}</dd>
            </div>
            <div>
              <dt>訂單 ID</dt>
              <dd>{{ detail.orderId ?? "尚無訂單" }}</dd>
            </div>
          </dl>

          <div v-if="detail.assignments.length" class="history-block">
            <h3>時段指派歷史</h3>
            <ol>
              <li
                v-for="assignment in detail.assignments"
                :key="`${assignment.revision}:${assignment.assignedAt}`"
              >
                <code>{{ assignment.previousSlotId ?? "初次指派" }} → {{ assignment.slotId }}</code>
                <span
                  >{{ assignment.assignedAt }} · {{ assignment.reasonCode }} · 修訂
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
                {{ loadingConfirmSlots ? "查詢時段中…" : "查詢可佔用時段" }}
              </button>
              <label v-if="confirmSlots.length" for="confirm-slot">要佔用的時段</label>
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
                {{ busy ? "處理中…" : "核對並確認成單" }}
              </button>
            </div>
            <p v-else class="muted">此會話沒有確認成單能力。</p>
            <button
              v-if="canReject"
              class="danger-button"
              type="button"
              :disabled="busy"
              @click="rejectReservation"
            >
              {{ busy ? "處理中…" : "拒絕申請" }}
            </button>
            <p class="muted">
              待確認申請不佔檔期。確認前請核對修訂與所選時段；確認於同一交易內佔用時段、建立唯一訂單與不可變快照，且不產生支付或定金。
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
              {{ busy ? "處理中…" : "取消預約" }}
            </button>
            <div v-if="canReschedule" class="reschedule-control">
              <button
                class="secondary-button"
                type="button"
                :disabled="busy || loadingSlots"
                @click="loadSlots"
              >
                {{ loadingSlots ? "查詢時段中…" : "查詢可用改期時段" }}
              </button>
              <label v-if="slots.length" for="new-slot">新時段</label>
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
                {{ busy ? "處理中…" : "確認改期" }}
              </button>
            </div>
            <span v-if="!canCancel && !canReschedule" class="muted"
              >此會話沒有取消或改期能力。</span
            >
          </div>
        </section>

        <section
          v-if="order && canReadOrders"
          class="booking-section order-section"
          aria-labelledby="order-title"
        >
          <div class="section-title-row">
            <h2 id="order-title">訂單快照與修訂</h2>
            <span>{{ order.lifecycle }} · 修訂 {{ order.currentRevision }}</span>
          </div>
          <dl class="booking-details">
            <div>
              <dt>訂單 ID</dt>
              <dd>
                <code>{{ order.id }}</code>
              </dd>
            </div>
            <div>
              <dt>套餐快照</dt>
              <dd>{{ order.snapshot.packageDisplayName }}</dd>
            </div>
            <div>
              <dt>價格快照</dt>
              <dd>
                {{ order.snapshot.amountMinorUnits }} {{ order.snapshot.currencyCode }} 最小貨幣單位
                · {{ order.snapshot.priceVersion }}
              </dd>
            </div>
            <div>
              <dt>原始時段</dt>
              <dd>{{ order.snapshot.startAt }} — {{ order.snapshot.endAt }}</dd>
            </div>
            <div>
              <dt>業務時區</dt>
              <dd>{{ order.snapshot.businessZone }} {{ order.snapshot.displayOffset }}</dd>
            </div>
            <div>
              <dt>快照建立時間</dt>
              <dd>{{ order.snapshot.capturedAt }}</dd>
            </div>
          </dl>
          <ol v-if="order.revisions.length" class="revision-list">
            <li v-for="revision in order.revisions" :key="revision.revision">
              <strong>修訂 {{ revision.revision }}</strong>
              <code>{{ revision.changedFields }}</code>
              <span>{{ revision.reasonCode }} · {{ revision.createdAt }}</span>
            </li>
          </ol>
        </section>
      </template>

      <p class="booking-footnote">
        改期只在新時段可用且版本匹配時提交；取消會釋放佔檔並追加訂單修訂。失聯後先查閱同一預約再重試同一命令。此工具不建立／確認新預約，也不處理退款、支付或生產通知。
      </p>
      <RouterLink class="back-link" to="/">返回工作台</RouterLink>
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
