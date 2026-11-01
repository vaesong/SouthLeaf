<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { authStoreKey } from "../auth/store";
import { AdminBookingApi, type OpenSlot } from "../bookings/api";
import { beijingParts, formatClock } from "../content/format";

const auth = inject(authStoreKey);
const canRead = computed(
  () => auth?.state.identity?.permissions.includes("RESERVATION_READ") ?? false,
);
const slots = ref<OpenSlot[]>([]);
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

function sameDay(slot: OpenSlot, date: Date) {
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

function openAt(date: Date, time: string) {
  return slots.value.some((slot) => sameDay(slot, date) && formatClock(slot.startAt) === time);
}

async function load() {
  if (!canRead.value) return;
  loading.value = true;
  errorText.value = "";
  const from = new Date(cursor.value);
  const to = new Date(cursor.value);
  to.setUTCDate(to.getUTCDate() + 7);
  try {
    slots.value = await new AdminBookingApi().openSlots(from.toISOString(), to.toISOString());
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

onMounted(load);
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
      </div>
    </div>
    <div class="note" style="margin-bottom: 18px">
      <b>说明</b>
      <span>档期和预约规则暂时需要联系技术人员在服务器上设置。</span>
    </div>
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
            <div v-if="openAt(day, time)" class="slot open">可预约</div>
          </div>
        </template>
        <p v-if="times.length === 0" class="card-pad">这一周还没有可预约的时段。</p>
      </div>
    </div>
  </div>
</template>
