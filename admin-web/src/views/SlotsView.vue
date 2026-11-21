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
import WeekBoard from "../components/WeekBoard.vue";
import SideDrawer from "../components/SideDrawer.vue";

const auth = inject(authStoreKey);
const canRead = computed(
  () => auth?.state.identity?.permissions.includes("RESERVATION_READ") ?? false,
);
const canWriteRules = computed(
  () => auth?.state.identity?.permissions.includes("BOOKING_RULES_WRITE") ?? false,
);
const rulesApi = new AdminBookingRulesApi();
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

watch(
  ruleForm,
  () => {
    ruleKey = null;
  },
  { deep: true },
);

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
  } catch (error) {
    currentRule.value = null;
    ruleError.value =
      error instanceof BookingRulesApiError ? error.message : "预约规则暂时读不出来。";
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

onMounted(() => void loadRules());
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>档期安排</h1>
        <p>周历上能看到客人要的时间。点空白的小时开放一档，一档多长由下面的规则决定。</p>
      </div>
    </div>
    <WeekBoard v-if="canRead" />
    <p v-else class="alert-line">当前账号不能查看档期。</p>
    <div class="card" style="margin-top: 18px">
      <div class="card-head">
        <h2>预约规则</h2>
        <p v-if="currentRule?.configured">当前正在使用的规则</p>
        <p v-else-if="currentRule">还没有保存过规则</p>
        <p v-else>规则还没有读出来</p>
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
        <p v-if="ruleError" class="alert-line" role="alert">
          {{ ruleError }}
          <button class="btn btn-secondary btn-sm" type="button" @click="loadRules">
            重新读取
          </button>
        </p>
        <p v-if="ruleNotice" data-testid="booking-rules-notice">{{ ruleNotice }}</p>
        <p v-if="!canWriteRules" class="hint">当前账号不能修改预约规则。</p>
        <div v-if="currentRule?.configured && currentRule.rules" class="grid-2">
          <p>最少提前预约：{{ currentRule.rules.minAdvanceHours }} 小时</p>
          <p>最远可预约：{{ currentRule.rules.maxAdvanceDays }} 天</p>
          <p>待确认过期：{{ currentRule.rules.pendingExpiryHours }} 小时</p>
          <p>单档时长：{{ currentRule.rules.slotDurationMinutes }} 分钟</p>
        </div>
        <p v-else-if="currentRule">保存后才会生效。表单会先填上常用数值。</p>
        <p v-else class="hint">读不出来时，周历上的开放仍会交给服务器判断。</p>
      </div>
    </div>
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
