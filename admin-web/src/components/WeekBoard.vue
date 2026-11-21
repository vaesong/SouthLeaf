<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { authStoreKey } from "../auth/store";
import { AdminBookingApi, BookingApiError, newBookingIdempotencyKey } from "../bookings/api";
import {
  AdminBookingSlotsApi,
  BookingSlotsApiError,
  type BoardSlot,
  type SlotAsk,
} from "../bookings/slotsApi";
import ConfirmDialog from "./ConfirmDialog.vue";
import { beijingParts, formatClock, formatRelative, tailNumber } from "../content/format";

withDefaults(defineProps<{ showRulesLink?: boolean }>(), { showRulesLink: false });

const auth = inject(authStoreKey);
const canRead = computed(
  () => auth?.state.identity?.permissions.includes("RESERVATION_READ") ?? false,
);
const canConfirm = computed(
  () => auth?.state.identity?.permissions.includes("RESERVATION_CONFIRM") ?? false,
);
const canReject = computed(
  () => auth?.state.identity?.permissions.includes("RESERVATION_REJECT") ?? false,
);
const canWriteSlots = computed(
  () => auth?.state.identity?.permissions.includes("SLOT_WRITE") ?? false,
);

const bookingApi = new AdminBookingApi();
const slotsApi = new AdminBookingSlotsApi();
const HOURS = Array.from({ length: 13 }, (_, index) => index + 9);
const ROW = 46;
const GRID_START = 9 * 60;
const GRID_END = 22 * 60;

const slots = ref<BoardSlot[]>([]);
const asks = ref<SlotAsk[]>([]);
const cursor = ref(startOfWeek(new Date()));
const loading = ref(false);
const busy = ref(false);
const errorText = ref("");
const notice = ref("");
const selectedKey = ref("");
const closeTarget = ref<BoardSlot | null>(null);
const review = ref<{
  title: string;
  message: string;
  confirmText: string;
  danger: boolean;
  run: () => Promise<void>;
} | null>(null);
const anchored = ref(false);
const keys = new Map<string, string>();
let openKey: string | null = null;
let closeKey: string | null = null;
let generation = 0;

interface CalBlock {
  key: string;
  kind: "ask" | "open" | "occupied" | "closed";
  startAt: string;
  endAt: string;
  top: number;
  height: number;
  slot: BoardSlot | null;
  asks: SlotAsk[];
}

function beijingMidnight(year: number, month: number, day: number) {
  return new Date(Date.UTC(year, month - 1, day) - 8 * 60 * 60 * 1000);
}

function startOfWeek(date: Date) {
  const parts = beijingParts(date);
  if (!parts) return new Date(date);
  const shifted = new Date(date.getTime() + 8 * 60 * 60 * 1000);
  const offset = (shifted.getUTCDay() + 6) % 7;
  const monday = beijingMidnight(parts.year, parts.month, parts.day);
  monday.setUTCDate(monday.getUTCDate() - offset);
  return monday;
}

function dayIso(value: string | Date) {
  const parts = beijingParts(value);
  if (!parts) return "";
  const month = String(parts.month).padStart(2, "0");
  const day = String(parts.day).padStart(2, "0");
  return `${parts.year}-${month}-${day}`;
}

function minutesOf(value: string) {
  const parts = beijingParts(value);
  if (!parts) return null;
  return parts.hour * 60 + parts.minute;
}

function whenLabel(start: string, end: string) {
  const parts = beijingParts(start);
  if (!parts) return "这个时段";
  return `${parts.month}月${parts.day}日 ${parts.weekday} ${formatClock(start)}–${formatClock(end)}`;
}

function askIdentity(ask: SlotAsk) {
  return `${ask.id}|${ask.slotId ?? ""}|${ask.startAt ?? ""}`;
}

function isToday(date: Date) {
  return dayIso(date) !== "" && dayIso(date) === dayIso(new Date());
}

function isBeforeToday(date: Date) {
  const today = dayIso(new Date());
  const key = dayIso(date);
  return !!key && !!today && key < today;
}

function atBeijing(date: Date, hour: number) {
  const parts = beijingParts(date);
  if (!parts) return new Date(NaN);
  return new Date(Date.UTC(parts.year, parts.month - 1, parts.day, hour) - 8 * 60 * 60 * 1000);
}

function layout(startAt: string, endAt: string) {
  const start = minutesOf(startAt);
  const end = minutesOf(endAt);
  if (start === null || end === null) return { top: 3, height: ROW - 6 };
  const topMin = Math.max(start, GRID_START);
  const endMin = Math.min(Math.max(end, topMin + 30), GRID_END);
  return {
    top: ((topMin - GRID_START) / 60) * ROW + 3,
    height: Math.max(32, ((endMin - topMin) / 60) * ROW - 6),
  };
}

function kindFor(slot: BoardSlot, matched: SlotAsk[]): CalBlock["kind"] {
  if (slot.availability === "OCCUPIED") return "occupied";
  if (matched.length > 0) return "ask";
  return "open";
}

const dayColumns = computed(() =>
  Array.from({ length: 7 }, (_, index) => {
    const date = new Date(cursor.value);
    date.setUTCDate(date.getUTCDate() + index);
    const iso = dayIso(date);
    const parts = beijingParts(date);
    return {
      date,
      iso,
      weekday: parts?.weekday ?? "",
      day: parts?.day ?? "",
      today: isToday(date),
      past: isBeforeToday(date),
      blocks: blocksOn(iso),
    };
  }),
);

function blocksOn(iso: string): CalBlock[] {
  const daySlots = slots.value.filter((slot) => dayIso(slot.startAt) === iso);
  const byId = new Map(daySlots.map((slot) => [slot.id, slot]));
  const attached = new Map<string, SlotAsk[]>();
  const placed = new Set<string>();
  function attach(slotId: string, ask: SlotAsk) {
    const list = attached.get(slotId) ?? [];
    list.push(ask);
    attached.set(slotId, list);
    placed.add(askIdentity(ask));
  }
  for (const ask of asks.value) {
    if (ask.slotId && byId.has(ask.slotId)) attach(ask.slotId, ask);
  }
  for (const ask of asks.value) {
    if (placed.has(askIdentity(ask)) || !ask.startAt || dayIso(ask.startAt) !== iso) continue;
    const same = daySlots.find(
      (slot) => slot.startAt === ask.startAt && slot.availability !== "OCCUPIED",
    );
    if (same) attach(same.id, ask);
  }
  const blocks: CalBlock[] = daySlots.map((slot) => {
    const matched = attached.get(slot.id) ?? [];
    const box = layout(slot.startAt, slot.endAt);
    return {
      key: `slot:${slot.id}`,
      kind: kindFor(slot, matched),
      startAt: slot.startAt,
      endAt: slot.endAt,
      top: box.top,
      height: box.height,
      slot,
      asks: matched,
    } satisfies CalBlock;
  });
  for (const ask of asks.value) {
    if (placed.has(askIdentity(ask)) || !ask.startAt || !ask.endAt || dayIso(ask.startAt) !== iso)
      continue;
    const box = layout(ask.startAt, ask.endAt);
    blocks.push({
      key: `ask:${askIdentity(ask)}`,
      kind: "closed",
      startAt: ask.startAt,
      endAt: ask.endAt,
      top: box.top,
      height: box.height,
      slot: null,
      asks: [ask],
    });
  }
  return blocks;
}

const selection = computed(() => {
  for (const day of dayColumns.value) {
    const block = day.blocks.find((item) => item.key === selectedKey.value);
    if (block) return block;
  }
  return null;
});

const outside = computed(() => {
  const visible = new Set(dayColumns.value.map((day) => day.iso));
  return asks.value.filter((ask) => !ask.startAt || !visible.has(dayIso(ask.startAt)));
});

const weekRange = computed(() => {
  const first = dayColumns.value[0];
  const last = dayColumns.value[6];
  if (!first || !last) return "";
  const left = beijingParts(first.date);
  const right = beijingParts(last.date);
  if (!left || !right) return "";
  return `${left.month}月${left.day}日 – ${right.month}月${right.day}日`;
});

const onThisWeek = computed(() => cursor.value.getTime() === startOfWeek(new Date()).getTime());

function blockText(block: CalBlock) {
  if (block.asks.length === 1) return tailNumber(block.asks[0].id);
  if (block.asks.length > 1) return `${block.asks.length} 单申请`;
  if (block.kind === "occupied") return "已约";
  return "空档";
}

function blockState(block: CalBlock) {
  if (block.kind === "occupied") return "已有确认";
  if (block.kind === "closed") return "还没开放";
  if (block.asks.length > 0) return "可以确认";
  return "可以预约";
}

function hourLabel(date: Date, hour: number) {
  const parts = beijingParts(date);
  if (!parts) return "开放档期";
  return `开放 ${parts.month}月${parts.day}日 ${String(hour).padStart(2, "0")}:00`;
}

function hourPast(date: Date, hour: number) {
  return atBeijing(date, hour).getTime() <= Date.now();
}

function keyFor(name: string) {
  let key = keys.get(name);
  if (!key) {
    key = newBookingIdempotencyKey();
    keys.set(name, key);
  }
  return key;
}

function clearKey(name: string) {
  keys.delete(name);
}

async function load() {
  if (!canRead.value) return;
  const token = ++generation;
  loading.value = true;
  errorText.value = "";
  const from = new Date(cursor.value);
  const to = new Date(cursor.value);
  to.setUTCDate(to.getUTCDate() + 7);
  try {
    const desk = await slotsApi.board(from.toISOString(), to.toISOString());
    if (token !== generation) return;
    slots.value = desk.slots;
    asks.value = desk.asks;
    if (
      selectedKey.value &&
      !dayColumns.value.some((day) => day.blocks.some((block) => block.key === selectedKey.value))
    )
      selectedKey.value = "";
    if (!anchored.value) {
      anchored.value = true;
      const visible = new Set(dayColumns.value.map((day) => day.iso));
      const inWeek = desk.asks.some((ask) => ask.startAt && visible.has(dayIso(ask.startAt)));
      if (!inWeek) {
        const next = desk.asks
          .filter((ask) => ask.startAt)
          .sort((left, right) => String(left.startAt).localeCompare(String(right.startAt)))[0];
        if (next?.startAt) {
          const target = startOfWeek(new Date(next.startAt));
          if (target.getTime() !== cursor.value.getTime()) {
            cursor.value = target;
            await load();
            return;
          }
        }
      }
    }
  } catch (error) {
    if (token !== generation) return;
    slots.value = [];
    asks.value = [];
    errorText.value = error instanceof Error ? error.message : "这一周的档期暂时读不出来。";
  } finally {
    if (token === generation) loading.value = false;
  }
}

function shift(daysToAdd: number) {
  const next = new Date(cursor.value);
  next.setUTCDate(next.getUTCDate() + daysToAdd);
  cursor.value = next;
  notice.value = "";
  void load();
}

function showThisWeek() {
  cursor.value = startOfWeek(new Date());
  notice.value = "";
  void load();
}

function showAsk(ask: SlotAsk) {
  if (!ask.startAt) return;
  cursor.value = startOfWeek(new Date(ask.startAt));
  notice.value = "";
  selectedKey.value = "";
  void load();
}

function selectBlock(block: CalBlock) {
  selectedKey.value = block.key;
  notice.value = "";
}

async function openExact(date: string, time: string) {
  if (!canWriteSlots.value) {
    notice.value = "当前账号不能开放档期。";
    return;
  }
  if (busy.value) return;
  busy.value = true;
  notice.value = "";
  openKey ??= newBookingIdempotencyKey();
  try {
    const opened = await slotsApi.open(date, time, openKey);
    openKey = null;
    notice.value = `已开放 ${whenLabel(opened.startAt, opened.endAt)}。`;
    await load();
  } catch (error) {
    const failure = error instanceof BookingSlotsApiError ? error : null;
    if (!failure?.outcomeUnknown) openKey = null;
    notice.value = failure?.message ?? "档期暂时无法开放。";
  } finally {
    busy.value = false;
  }
}

function openHour(date: Date, hour: number) {
  const iso = dayIso(date);
  const covering = dayColumns.value
    .find((day) => day.iso === iso)
    ?.blocks.find((block) => {
      const start = minutesOf(block.startAt);
      const end = minutesOf(block.endAt);
      return start !== null && end !== null && start < (hour + 1) * 60 && end > hour * 60;
    });
  if (covering) {
    selectedKey.value = covering.key;
    notice.value = "这个小时已经在档期里。";
    return;
  }
  void openExact(iso, `${String(hour).padStart(2, "0")}:00`);
}

function openAsk(ask: SlotAsk) {
  if (!ask.startAt) return;
  const parts = beijingParts(ask.startAt);
  if (!parts) return;
  const month = String(parts.month).padStart(2, "0");
  const day = String(parts.day).padStart(2, "0");
  void openExact(`${parts.year}-${month}-${day}`, formatClock(ask.startAt));
}

function confirmAsk(ask: SlotAsk, slot: BoardSlot) {
  if (!canConfirm.value || busy.value) return;
  review.value = {
    title: "确认预约",
    message: `按客人要的时间确认。确认后会占住这个时段：${tailNumber(ask.id)}，${whenLabel(slot.startAt, slot.endAt)}。本阶段不收款。`,
    confirmText: "确认",
    danger: false,
    run: () =>
      writeAsk("confirm", ask, () =>
        bookingApi.confirm(ask.id, slot.id, `"r${ask.version}"`, keyFor(`confirm:${ask.id}`)),
      ),
  };
}

function rejectAsk(ask: SlotAsk) {
  if (!canReject.value || busy.value) return;
  review.value = {
    title: "拒绝申请",
    message: `拒绝后不会占住档期。${tailNumber(ask.id)}。`,
    confirmText: "拒绝申请",
    danger: true,
    run: () =>
      writeAsk("reject", ask, () =>
        bookingApi.reject(ask.id, `"r${ask.version}"`, keyFor(`reject:${ask.id}`)),
      ),
  };
}

async function runReview() {
  const current = review.value;
  if (!current || busy.value) return;
  review.value = null;
  await current.run();
}

async function writeAsk(action: "confirm" | "reject", ask: SlotAsk, run: () => Promise<unknown>) {
  busy.value = true;
  notice.value = "";
  try {
    await run();
    clearKey(`${action}:${ask.id}`);
    notice.value =
      action === "confirm"
        ? `已确认 ${tailNumber(ask.id)}，占住 ${selection.value ? whenLabel(selection.value.startAt, selection.value.endAt) : "这个时段"}。`
        : `已拒绝 ${tailNumber(ask.id)}。`;
    selectedKey.value = "";
    await load();
  } catch (error) {
    const failure = error instanceof BookingApiError ? error : null;
    if (!failure?.outcomeUnknown) clearKey(`${action}:${ask.id}`);
    notice.value = failure?.outcomeUnknown
      ? "操作结果尚未确认。请先看这一格再试。"
      : (failure?.message ?? "这一下没有完成。");
  } finally {
    busy.value = false;
  }
}

async function confirmClose() {
  const slot = closeTarget.value;
  if (!slot || busy.value) return;
  busy.value = true;
  closeKey ??= newBookingIdempotencyKey();
  try {
    await slotsApi.close(slot.id, slot.revision, closeKey);
    closeKey = null;
    closeTarget.value = null;
    selectedKey.value = "";
    notice.value = `已关闭 ${whenLabel(slot.startAt, slot.endAt)}。`;
    await load();
  } catch (error) {
    const failure = error instanceof BookingSlotsApiError ? error : null;
    if (!failure?.outcomeUnknown) closeKey = null;
    notice.value = failure?.message ?? "这个时段暂时不能关闭。";
  } finally {
    busy.value = false;
  }
}

onMounted(() => void load());
</script>

<template>
  <section>
    <div class="page-head" style="margin-bottom: 8px">
      <div>
        <h2>这一周</h2>
        <p>{{ weekRange }}。客人要的时间标在格子上。点空白的小时可以开放一档。</p>
      </div>
      <div class="actions">
        <button class="btn btn-secondary btn-sm" type="button" @click="shift(-7)">上一周</button>
        <button
          class="btn btn-secondary btn-sm"
          type="button"
          :disabled="onThisWeek"
          @click="showThisWeek"
        >
          本周
        </button>
        <button class="btn btn-secondary btn-sm" type="button" @click="shift(7)">下一周</button>
      </div>
    </div>
    <p v-if="notice" class="notice-line" role="status">
      {{ notice }}
      <RouterLink v-if="showRulesLink && notice.includes('预约规则')" to="/slots"
        >去设置预约规则</RouterLink
      >
    </p>
    <p v-if="!canRead" class="alert-line">当前账号不能查看档期。</p>
    <p v-if="errorText" class="alert-line" role="alert">
      {{ errorText }}
      <button class="btn btn-secondary btn-sm" type="button" @click="load">重新读取</button>
    </p>
    <div v-if="outside.length" class="card card-pad" style="margin-bottom: 12px">
      <p v-for="ask in outside" :key="askIdentity(ask)">
        <template v-if="ask.startAt && ask.endAt">
          {{ tailNumber(ask.id) }} 要 {{ whenLabel(ask.startAt, ask.endAt) }}，不在这一周。
          <button class="btn btn-secondary btn-sm" type="button" @click="showAsk(ask)">
            打开那一周
          </button>
        </template>
        <template v-else>{{ tailNumber(ask.id) }} 还没有选具体时段。</template>
      </p>
    </div>
    <div class="cal-legend">
      <span><i class="swatch ask"></i>有申请</span>
      <span><i class="swatch open"></i>空档</span>
      <span><i class="swatch occupied"></i>已约</span>
      <span><i class="swatch closed"></i>客人要了，档期还没开</span>
    </div>
    <p v-if="loading">正在读取这一周…</p>
    <div v-else class="card cal-scroll">
      <div class="cal">
        <div>
          <div class="cal-head"></div>
          <div v-for="hour in HOURS" :key="hour" class="cal-tick">
            {{ String(hour).padStart(2, "0") }}:00
          </div>
        </div>
        <div v-for="day in dayColumns" :key="day.iso">
          <div class="cal-head" :class="{ today: day.today }">
            {{ day.weekday }}<b>{{ day.day }}</b>
          </div>
          <div class="cal-col" :style="{ height: `${HOURS.length * ROW}px` }">
            <button
              v-for="(hour, index) in HOURS"
              :key="hour"
              class="cal-hour"
              type="button"
              :style="{ height: `${ROW}px` }"
              :disabled="!canWriteSlots || day.past || hourPast(day.date, hour) || busy"
              :aria-label="
                day.past || hourPast(day.date, hour) ? '已过去' : hourLabel(day.date, hour)
              "
              @click="openHour(day.date, hour)"
            >
              <span v-if="index % 2 === 0">开放</span>
            </button>
            <button
              v-for="block in day.blocks"
              :key="block.key"
              class="cal-block"
              :class="[block.kind, { on: block.key === selectedKey }]"
              type="button"
              :style="{ top: `${block.top}px`, height: `${block.height}px` }"
              @click="selectBlock(block)"
            >
              <b>{{ formatClock(block.startAt) }}</b>
              <span>{{ blockText(block) }}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
    <div v-if="selection" class="card card-pad cal-panel">
      <p class="hint">{{ blockState(selection) }}</p>
      <p class="cal-clock">
        {{ formatClock(selection.startAt) }}–{{ formatClock(selection.endAt) }}
      </p>
      <p>{{ whenLabel(selection.startAt, selection.endAt) }}</p>
      <div v-for="ask in selection.asks" :key="askIdentity(ask)" class="cal-ask">
        <p>{{ tailNumber(ask.id) }} · 保留到 {{ formatRelative(ask.expiresAt) }}</p>
        <div class="actions">
          <button
            v-if="canConfirm && selection.slot && selection.kind !== 'occupied'"
            class="btn btn-primary"
            type="button"
            :disabled="busy"
            @click="confirmAsk(ask, selection.slot)"
          >
            按这个时间确认
          </button>
          <button
            v-if="canWriteSlots && !selection.slot"
            class="btn btn-accent"
            type="button"
            :disabled="busy"
            @click="openAsk(ask)"
          >
            开放这个开始时间
          </button>
          <button
            v-if="canReject"
            class="btn btn-danger"
            type="button"
            :disabled="busy"
            @click="rejectAsk(ask)"
          >
            拒绝申请
          </button>
        </div>
        <p v-if="selection.kind === 'occupied'" class="hint">这一档已经有确认的预约。</p>
        <p v-if="!canConfirm && selection.slot && selection.kind !== 'occupied'" class="hint">
          当前账号不能确认预约。
        </p>
      </div>
      <div v-if="selection.asks.length === 0" class="actions">
        <button
          v-if="canWriteSlots && selection.slot && selection.kind === 'open'"
          class="btn btn-secondary"
          type="button"
          :disabled="busy"
          @click="closeTarget = selection.slot"
        >
          关闭这一档
        </button>
        <p v-else-if="selection.kind === 'occupied'" class="hint">这一档已经有确认的预约。</p>
      </div>
    </div>
    <p v-if="!canWriteSlots && canRead" class="hint">当前账号只能查看档期，不能开放或关闭。</p>
    <ConfirmDialog
      :open="review !== null"
      :title="review?.title ?? ''"
      :message="review?.message ?? ''"
      :confirm-text="review?.confirmText ?? '确认'"
      :danger="review?.danger"
      :confirm-disabled="busy"
      @cancel="review = null"
      @confirm="runReview"
    />
    <ConfirmDialog
      :open="closeTarget !== null"
      title="关闭档期"
      message="关闭后，客户就不能再约这个时段。"
      confirm-text="关闭档期"
      danger
      :confirm-disabled="busy"
      @cancel="closeTarget = null"
      @confirm="confirmClose"
    />
  </section>
</template>
