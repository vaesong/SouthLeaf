<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from "vue";
import { authStoreKey } from "../auth/store";
import {
  AdminBookingRulesApi,
  BookingRulesApiError,
  newBookingIdempotencyKey,
  type OperatingRuleCurrent,
  type OperatingRules,
} from "../bookings/rulesApi";
import { AdminBookingSlotsApi, slotFailureText, type BoardSlot } from "../bookings/slotsApi";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import SegmentedControl from "../components/SegmentedControl.vue";
import SideDrawer from "../components/SideDrawer.vue";
import { beijingParts, formatClock, formatDate } from "../content/format";

const auth = inject(authStoreKey);
const canRead = computed(
  () => auth?.state.identity?.permissions.includes("RESERVATION_READ") ?? false,
);
const canWriteRules = computed(
  () => auth?.state.identity?.permissions.includes("BOOKING_RULES_WRITE") ?? false,
);
const canWriteSlots = computed(
  () => auth?.state.identity?.permissions.includes("SLOT_WRITE") ?? false,
);
const rulesApi = new AdminBookingRulesApi();
const slotsApi = new AdminBookingSlotsApi();
const START_TIMES = [
  "09:00",
  "10:00",
  "11:00",
  "12:00",
  "13:00",
  "14:00",
  "15:00",
  "16:00",
  "17:00",
  "18:00",
];
const currentRule = ref<OperatingRuleCurrent | null>(null);
const ruleError = ref("");
const ruleNotice = ref("");
const ruleDrawer = ref(false);
const ruleBusy = ref(false);
const ruleForm = ref<OperatingRules>(emptyRuleForm());
let ruleKey: string | null = null;

function emptyRuleForm(): OperatingRules {
  return {
    minAdvanceHours: 24,
    maxAdvanceDays: 30,
    pendingExpiryHours: 48,
    slotDurationMinutes: 120,
  };
}
const slots = ref<BoardSlot[]>([]);
const openDrawer = ref(false);
const openMode = ref("one");
const singleDate = ref("");
const singleTime = ref("10:00");
const selectedDates = ref<string[]>([]);
const selectedTimes = ref<string[]>(["10:00"]);
const openBusy = ref(false);
const openError = ref("");
const openNotice = ref("");
const closeTarget = ref<BoardSlot | null>(null);
const closeBusy = ref(false);
const closeError = ref("");
let openKey: string | null = null;
let closeKey: string | null = null;
const cursor = ref(startOfWeek(new Date()));
const errorText = ref("");
const loading = ref(false);

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

const days = computed(() =>
  Array.from({ length: 7 }, (_, index) => {
    const date = new Date(cursor.value);
    date.setUTCDate(date.getUTCDate() + index);
    return date;
  }),
);

const times = computed(() => {
  const values = new Set(slots.value.map((slot) => formatClock(slot.startAt)));
  return [...values].filter((value) => value !== "时间待定").sort();
});

function dayLabel(date: Date) {
  const parts = beijingParts(date);
  return parts?.weekday ?? "";
}

function dayNumber(date: Date) {
  return beijingParts(date)?.day ?? "";
}

function isToday(date: Date) {
  const left = beijingParts(date);
  const right = beijingParts(new Date());
  return (
    !!left &&
    !!right &&
    left.year === right.year &&
    left.month === right.month &&
    left.day === right.day
  );
}

function sameDay(slot: BoardSlot, date: Date) {
  const left = beijingParts(slot.startAt);
  const right = beijingParts(date);
  return (
    !!left &&
    !!right &&
    left.year === right.year &&
    left.month === right.month &&
    left.day === right.day
  );
}

function cellAt(date: Date, time: string) {
  return slots.value.find((slot) => sameDay(slot, date) && formatClock(slot.startAt) === time);
}

function cellLabel(slot: BoardSlot) {
  if (slot.availability === "OCCUPIED") return "已占用";
  if (slot.availability === "PENDING") return "待确认";
  return "可预约";
}

function cellClass(slot: BoardSlot) {
  if (slot.availability === "OCCUPIED") return "booked";
  if (slot.availability === "PENDING") return "held";
  return "open";
}

function isoDate(date: Date) {
  const parts = beijingParts(date);
  if (!parts) return "";
  return `${parts.year}-${String(parts.month).padStart(2, "0")}-${String(parts.day).padStart(2, "0")}`;
}

function toggle(list: string[], value: string) {
  const next = list.includes(value) ? list.filter((item) => item !== value) : [...list, value];
  next.sort();
  return next;
}

function previewLines() {
  const duration = currentRule.value?.rules?.slotDurationMinutes ?? 120;
  const dates = openMode.value === "one" ? [singleDate.value] : selectedDates.value;
  const times = openMode.value === "one" ? [singleTime.value] : selectedTimes.value;
  return dates
    .filter((date) => date)
    .flatMap((date) =>
      times.map((time) => {
        const start = new Date(`${date}T${time}:00+08:00`);
        const end = new Date(start.getTime() + duration * 60 * 1000);
        return `${formatDate(start.toISOString())} ${time}–${formatClock(end.toISOString())}`;
      }),
    );
}

async function load() {
  if (!canRead.value) return;
  loading.value = true;
  errorText.value = "";
  const from = new Date(cursor.value);
  const to = new Date(cursor.value);
  to.setUTCDate(to.getUTCDate() + 7);
  try {
    slots.value = await slotsApi.board(from.toISOString(), to.toISOString());
  } catch {
    slots.value = [];
    errorText.value = "这一周的档期暂时读不出来。";
  } finally {
    loading.value = false;
  }
}

function shift(daysToAdd: number) {
  const next = new Date(cursor.value);
  next.setUTCDate(next.getUTCDate() + daysToAdd);
  cursor.value = next;
  void load();
}

watch(
  () =>
    JSON.stringify({
      mode: openMode.value,
      dates: openMode.value === "one" ? [singleDate.value] : [...selectedDates.value],
      times: openMode.value === "one" ? [singleTime.value] : [...selectedTimes.value],
    }),
  () => {
    openKey = null;
  },
);

watch(
  ruleForm,
  () => {
    ruleKey = null;
  },
  { deep: true },
);

function openSlotEditor() {
  openError.value = "";
  openNotice.value = "";
  selectedDates.value = [];
  openDrawer.value = true;
}

async function saveSlots() {
  if (openBusy.value) return;
  if (!currentRule.value?.configured) {
    openError.value = "请先设置预约规则";
    return;
  }
  const dates = openMode.value === "one" ? [singleDate.value] : selectedDates.value;
  const times = openMode.value === "one" ? [singleTime.value] : selectedTimes.value;
  if (dates.some((date) => !date) || times.length === 0) {
    openError.value = "请选择日期和开始时间。";
    return;
  }
  openBusy.value = true;
  openError.value = "";
  openKey ??= newBookingIdempotencyKey();
  let weekIncomplete = false;
  try {
    if (openMode.value === "one") {
      await slotsApi.open(dates[0], times[0], openKey);
      openNotice.value = "已开放";
      openDrawer.value = false;
    } else {
      const items = await slotsApi.openWeek(dates, times, openKey);
      const failed = items.filter((item) => !item.ok);
      weekIncomplete = failed.length > 0;
      if (failed.length === 0) {
        openNotice.value = "已开放";
        openDrawer.value = false;
      } else {
        openError.value = failed
          .map(
            (item) =>
              `${formatDate(`${item.date}T${item.startTime}:00+08:00`)} ${item.startTime}：${slotFailureText(item.reason)}`,
          )
          .join("；");
      }
    }
    if (!weekIncomplete) openKey = null;
    await load();
  } catch (error) {
    openError.value = error instanceof Error ? error.message : "档期暂时无法开放。";
  } finally {
    openBusy.value = false;
  }
}

function askClose(slot: BoardSlot) {
  if (slot.availability !== "OPEN" || !canWriteSlots.value) return;
  closeError.value = "";
  closeTarget.value = slot;
}

async function confirmClose() {
  const slot = closeTarget.value;
  if (!slot || closeBusy.value) return;
  closeBusy.value = true;
  closeError.value = "";
  closeKey ??= newBookingIdempotencyKey();
  try {
    await slotsApi.close(slot.id, slot.revision, closeKey);
    closeKey = null;
    closeTarget.value = null;
    await load();
  } catch (error) {
    closeError.value = error instanceof Error ? error.message : "这个时段暂时不能关闭。";
  } finally {
    closeBusy.value = false;
  }
}

function openRuleEditor() {
  const current = currentRule.value;
  ruleForm.value = current?.configured && current.rules ? { ...current.rules } : emptyRuleForm();
  ruleNotice.value = "";
  ruleDrawer.value = true;
}

async function loadRules() {
  if (!canRead.value) return;
  ruleError.value = "";
  try {
    currentRule.value = await rulesApi.current();
  } catch {
    currentRule.value = null;
    ruleError.value = "预约规则暂时读不出来。";
  }
}

async function saveRules() {
  if (!currentRule.value || ruleBusy.value) return;
  ruleBusy.value = true;
  ruleError.value = "";
  ruleKey ??= newBookingIdempotencyKey();
  try {
    await rulesApi.save(currentRule.value.version, ruleForm.value, ruleKey);
    ruleKey = null;
    ruleNotice.value = "已生效，只影响之后的新档期与新预约";
    ruleDrawer.value = false;
    await loadRules();
  } catch (error) {
    ruleError.value =
      error instanceof BookingRulesApiError ? error.message : "预约规则暂时无法保存。";
  } finally {
    ruleBusy.value = false;
  }
}

onMounted(async () => {
  await Promise.all([load(), loadRules()]);
});
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>档期安排</h1>
        <p>绿色格子是可以预约的时段。</p>
      </div>
      <div class="actions">
        <button class="btn btn-secondary btn-sm" type="button" @click="shift(-7)">上一周</button>
        <button class="btn btn-secondary btn-sm" type="button" @click="shift(7)">下一周</button>
        <button
          class="btn btn-accent btn-sm"
          type="button"
          data-testid="open-slot"
          :disabled="!canWriteSlots"
          @click="openSlotEditor"
        >
          开放新档期
        </button>
      </div>
    </div>
    <p v-if="openNotice" class="notice-line">{{ openNotice }}</p>
    <p v-if="!canRead" class="alert-line">当前账号不能查看档期。</p>
    <p v-if="errorText" class="alert-line" role="alert">{{ errorText }}</p>
    <p v-if="loading">正在读取档期…</p>
    <div v-else class="card week-wrap">
      <div class="week">
        <div class="h"></div>
        <div
          v-for="day in days"
          :key="day.toISOString()"
          class="h"
          :class="{ today: isToday(day) }"
        >
          {{ dayLabel(day) }}<b>{{ dayNumber(day) }}</b>
        </div>
        <template v-for="time in times" :key="time">
          <div class="t">{{ time }}</div>
          <div v-for="day in days" :key="`${day.toISOString()}-${time}`" class="c">
            <button
              v-if="cellAt(day, time)"
              class="slot"
              :class="cellClass(cellAt(day, time)!)"
              type="button"
              :disabled="cellAt(day, time)!.availability !== 'OPEN' || !canWriteSlots"
              @click="askClose(cellAt(day, time)!)"
            >
              {{ cellLabel(cellAt(day, time)!) }}
            </button>
          </div>
        </template>
        <p v-if="times.length === 0" class="card-pad">这一周还没有可预约的时段。</p>
      </div>
    </div>
    <div class="card" style="margin-top: 18px">
      <div class="card-head">
        <h2>预约规则</h2>
        <p v-if="currentRule?.configured">当前正在使用的规则</p>
        <p v-else>还没有保存过规则</p>
        <div class="right">
          <button
            class="btn btn-secondary btn-sm"
            type="button"
            data-testid="booking-rules-edit"
            :disabled="!canWriteRules || !currentRule"
            @click="openRuleEditor"
          >
            修改规则
          </button>
        </div>
      </div>
      <div class="card-pad">
        <p v-if="ruleError" class="alert-line" role="alert">{{ ruleError }}</p>
        <p v-if="ruleNotice" data-testid="booking-rules-notice">{{ ruleNotice }}</p>
        <p v-if="!canWriteRules" class="hint">当前账号不能修改预约规则。</p>
        <div v-if="currentRule?.configured && currentRule.rules" class="grid-2">
          <p>最少提前预约：{{ currentRule.rules.minAdvanceHours }} 小时</p>
          <p>最远可预约：{{ currentRule.rules.maxAdvanceDays }} 天</p>
          <p>待确认过期：{{ currentRule.rules.pendingExpiryHours }} 小时</p>
          <p>单档时长：{{ currentRule.rules.slotDurationMinutes }} 分钟</p>
        </div>
        <p v-else-if="currentRule">保存后才会生效。表单会先填上常用数值。</p>
      </div>
    </div>
    <SideDrawer :open="openDrawer" title="开放新档期" @close="openDrawer = false">
      <div class="stack">
        <SegmentedControl
          v-model="openMode"
          label="开放方式"
          :options="[
            { value: 'one', label: '单个' },
            { value: 'week', label: '按周批量' },
          ]"
        />
        <p v-if="!currentRule?.configured" class="alert-line">请先设置预约规则</p>
        <div v-if="openMode === 'one'" class="field">
          <label>日期</label>
          <input v-model="singleDate" class="input" type="date" data-testid="slot-date" />
        </div>
        <div v-else class="field">
          <label>这一周的哪几天</label>
          <label v-for="day in days" :key="day.toISOString()" class="check">
            <input
              type="checkbox"
              :checked="selectedDates.includes(isoDate(day))"
              @change="selectedDates = toggle(selectedDates, isoDate(day))"
            />
            {{ dayLabel(day) }} {{ dayNumber(day) }}
          </label>
        </div>
        <div class="field">
          <label>开始时间</label>
          <label v-for="time in START_TIMES" :key="time" class="check">
            <input
              v-if="openMode === 'week'"
              type="checkbox"
              :checked="selectedTimes.includes(time)"
              @change="selectedTimes = toggle(selectedTimes, time)"
            />
            <input v-else type="radio" name="slot-start" :value="time" v-model="singleTime" />
            {{ time }}
          </label>
        </div>
        <div>
          <p>将生成的时段</p>
          <p v-for="line in previewLines()" :key="line">{{ line }}</p>
          <p v-if="previewLines().length === 0">还没有选择日期和开始时间。</p>
        </div>
        <p v-if="openError" class="alert-line" role="alert">{{ openError }}</p>
        <button
          class="btn btn-accent"
          type="button"
          data-testid="slot-save"
          :disabled="openBusy || !currentRule?.configured"
          @click="saveSlots"
        >
          开放
        </button>
      </div>
    </SideDrawer>
    <ConfirmDialog
      :open="closeTarget !== null"
      title="关闭档期"
      message="关闭后，客户就不能再约这个时段。"
      confirm-text="关闭档期"
      danger
      :confirm-disabled="closeBusy"
      @cancel="closeTarget = null"
      @confirm="confirmClose"
    />
    <p v-if="closeError" class="alert-line" role="alert">{{ closeError }}</p>
    <SideDrawer :open="ruleDrawer" title="修改预约规则" @close="ruleDrawer = false">
      <div class="stack">
        <div class="field">
          <label>最少提前预约（小时）</label>
          <input
            v-model.number="ruleForm.minAdvanceHours"
            class="input"
            type="number"
            min="0"
            max="168"
            data-testid="booking-rules-advance-hours"
          />
        </div>
        <div class="field">
          <label>最远可预约（天）</label>
          <input
            v-model.number="ruleForm.maxAdvanceDays"
            class="input"
            type="number"
            min="1"
            max="180"
          />
        </div>
        <div class="field">
          <label>待确认过期（小时）</label>
          <input
            v-model.number="ruleForm.pendingExpiryHours"
            class="input"
            type="number"
            min="1"
            max="168"
          />
        </div>
        <div class="field">
          <label>单档时长（分钟）</label>
          <input
            v-model.number="ruleForm.slotDurationMinutes"
            class="input"
            type="number"
            min="30"
            max="480"
          />
        </div>
        <button
          class="btn btn-accent"
          type="button"
          data-testid="booking-rules-save"
          :disabled="ruleBusy"
          @click="saveRules"
        >
          保存规则
        </button>
      </div>
    </SideDrawer>
  </div>
</template>
