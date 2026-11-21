<script setup lang="ts">
import { computed, onMounted } from "vue";
import CatalogSaveButtons from "../components/CatalogSaveButtons.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import {
  COPY_SCREENS,
  PRESENTATION_FIELDS,
  readPresentationLine,
  writePresentationLine,
} from "../content/catalogDraft";
import { PRESENTATION_DEFAULTS } from "../content/presentationDefaults";
import { useCatalogAccess, useCatalogLeaveGuard } from "../content/useCatalog";

const { store, canEdit } = useCatalogAccess();
const leave = useCatalogLeaveGuard(store);
const leaveOpen = leave.open;
const draft = computed(() => store.state.draft);

const cards = computed(() =>
  COPY_SCREENS.map((screen) => ({
    title: screen.title,
    fields: PRESENTATION_FIELDS.filter(
      (field) =>
        Object.prototype.hasOwnProperty.call(PRESENTATION_DEFAULTS, field.key) &&
        screen.prefixes.some((prefix) => field.key.startsWith(prefix)),
    ),
  })).filter((card) => card.fields.length > 0),
);

function line(key: string) {
  return readPresentationLine(draft.value?.presentation, key, PRESENTATION_DEFAULTS);
}

function wide(key: string) {
  const text = PRESENTATION_DEFAULTS[key] ?? "";
  return text.length > 18 || text.includes("\n");
}

function setLine(key: string, value: string) {
  const current = draft.value;
  if (!current) return;
  if (!current.presentation) current.presentation = {};
  writePresentationLine(current.presentation, key, value, PRESENTATION_DEFAULTS);
}

onMounted(() => store.load());
</script>

<template>
  <div v-if="draft" class="stack">
    <div class="page-head">
      <div>
        <h1>界面文案</h1>
        <p>
          每张卡片对应小程序里的一个页面。格子里已经是现在显示的句子。改完保存，再在顶栏发布。某一格清空并发布后，那一句不再显示。
        </p>
      </div>
      <div class="actions">
        <CatalogSaveButtons :disabled="!canEdit" />
      </div>
    </div>
    <div v-for="card in cards" :key="card.title" class="card">
      <div class="card-head">
        <h2>{{ card.title }}</h2>
        <p v-if="card.title === '预约'">不收款说明也会出现在我的和预约详情。</p>
      </div>
      <div class="card-pad grid-2">
        <div
          v-for="field in card.fields"
          :key="field.key"
          class="field"
          :class="{ 'span-2': wide(field.key) }"
        >
          <label>{{ field.label }}</label>
          <textarea
            v-if="wide(field.key)"
            class="textarea"
            rows="3"
            maxlength="400"
            :disabled="!canEdit"
            :data-testid="`catalog-copy-${field.key}`"
            :value="line(field.key)"
            @input="setLine(field.key, ($event.target as HTMLTextAreaElement).value)"
          ></textarea>
          <input
            v-else
            class="input"
            maxlength="400"
            :disabled="!canEdit"
            :data-testid="`catalog-copy-${field.key}`"
            :value="line(field.key)"
            @input="setLine(field.key, ($event.target as HTMLInputElement).value)"
          />
          <p v-if="line(field.key) === ''" class="hint">小程序上不显示这句。</p>
        </div>
      </div>
    </div>
  </div>
  <p v-else-if="store.state.status === 'loading'">正在读取草稿…</p>
  <ConfirmDialog
    :open="leaveOpen"
    title="有未保存的修改"
    message="还有没保存的修改。确定离开这一页？"
    confirm-text="离开"
    @cancel="leave.stay"
    @confirm="leave.allow"
  />
</template>
