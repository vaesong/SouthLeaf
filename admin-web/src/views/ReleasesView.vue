<script setup lang="ts">
import { onMounted, ref } from "vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import StatusChip from "../components/StatusChip.vue";
import type { CatalogVersionView } from "../content/api";
import { requireContentApi } from "../content/inject";
import { describeFailure } from "../content/messages";
import { formatDateTime } from "../content/format";
import { useCatalogAccess } from "../content/useCatalog";

const api = requireContentApi();
const { store, canEdit, canPublish } = useCatalogAccess();
const withdrawOpen = ref(false);
const historyOpen = ref(false);
const historyTitle = ref("");
const historyMessage = ref("");
const versions = ref<CatalogVersionView[]>([]);
const selected = ref<number | null>(null);
const errorText = ref("");
const viewing = ref("");
let historyAction: (() => Promise<void>) | null = null;

function askHistory(title: string, message: string, action: () => Promise<void>) {
  historyTitle.value = title;
  historyMessage.value = message;
  historyAction = action;
  historyOpen.value = true;
}

function cancelHistory() {
  historyOpen.value = false;
  historyAction = null;
}

async function runHistory() {
  const action = historyAction;
  historyOpen.value = false;
  historyAction = null;
  if (action) await action();
}

async function refresh() {
  errorText.value = "";
  try {
    versions.value = await api.listCatalogVersions();
    selected.value = versions.value[0]?.revision ?? null;
  } catch (error) {
    errorText.value = describeFailure(error).text;
  }
}

function loadIntoEditor() {
  const revision = selected.value;
  if (revision === null || store.state.revision === null) return;
  askHistory(
    "载入到编辑器",
    "把以前保存的内容放进编辑区？还没保存的修改会被换掉。公开页面要等再次发布才会变。",
    async () => {
      if (store.state.revision === null) return;
      store.state.busy = "history";
      try {
        const historic = await api.getCatalogVersion(revision);
        store.applyHistoricDraft(historic, store.state.revision);
      } catch (error) {
        errorText.value = describeFailure(error).text;
      } finally {
        store.state.busy = "";
      }
    },
  );
}

function restoreAsDraft() {
  const revision = selected.value;
  if (revision === null || !canEdit.value) return;
  askHistory(
    "恢复为草稿",
    "把这一版读入当前草稿并立即保存。公开页面要等再次发布才会变。",
    async () => {
      try {
        const historic = await api.getCatalogVersion(revision);
        if (store.state.revision === null) return;
        store.applyHistoricDraft(historic, store.state.revision);
        await store.save();
      } catch (error) {
        errorText.value = describeFailure(error).text;
      }
    },
  );
}

async function inspect() {
  if (selected.value === null) return;
  try {
    const historic = await api.getCatalogVersion(selected.value);
    const savedAt = versions.value.find((item) => item.revision === historic.revision)?.updatedAt;
    viewing.value = `${historic.draft.brand.name || "未命名"}${savedAt ? ` · ${formatDateTime(savedAt)}` : ""}`;
  } catch (error) {
    errorText.value = describeFailure(error).text;
  }
}

onMounted(async () => {
  await store.load();
  await refresh();
});
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>发布记录</h1>
        <p>
          每次保存都会留下一版草稿。可以查看当时的内容，或把它恢复成当前草稿。下架不会自动回到旧版。
        </p>
      </div>
      <div class="actions">
        <button
          class="btn btn-danger-ghost"
          type="button"
          data-testid="catalog-withdraw"
          :disabled="!canPublish || store.state.busy !== ''"
          @click="withdrawOpen = true"
        >
          下架当前目录
        </button>
      </div>
    </div>
    <p v-if="errorText" class="alert-line" role="alert">{{ errorText }}</p>
    <p v-if="viewing" class="notice-line">正在查看：{{ viewing }}。这一步没有改当前草稿。</p>
    <div class="card" style="overflow: hidden">
      <table class="table">
        <thead>
          <tr>
            <th>保存时间</th>
            <th>操作人</th>
            <th>状态</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="store.unpublishedLabel">
            <td><b>当前草稿</b></td>
            <td>{{ store.unpublishedLabel }}</td>
            <td>—</td>
            <td><StatusChip tone="draft" text="未发布" /></td>
          </tr>
          <tr v-for="version in versions" :key="version.revision">
            <td>
              <b>{{ formatDateTime(version.updatedAt) }}</b>
            </td>
            <td>管理员</td>
            <td>
              <StatusChip
                :tone="version.revision === store.state.revision ? 'live' : 'off'"
                :text="version.revision === store.state.revision ? '当前草稿' : '历史'"
              />
            </td>
            <td style="text-align: right">
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                @click="
                  selected = version.revision;
                  inspect();
                "
              >
                查看
              </button>
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                :disabled="!canEdit"
                @click="
                  selected = version.revision;
                  restoreAsDraft();
                "
              >
                恢复为草稿
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="card card-pad" style="margin-top: 18px">
      <div class="field">
        <label>选择以前保存的内容</label>
        <select v-model.number="selected" class="select" data-testid="catalog-history-revision">
          <option v-for="version in versions" :key="version.revision" :value="version.revision">
            {{ formatDateTime(version.updatedAt) }}
          </option>
        </select>
      </div>
      <button
        class="btn btn-secondary"
        type="button"
        style="margin-top: 12px"
        data-testid="catalog-load-history"
        :disabled="store.state.busy !== ''"
        @click="loadIntoEditor"
      >
        载入到编辑器
      </button>
    </div>
    <ConfirmDialog
      :open="historyOpen"
      :title="historyTitle"
      :message="historyMessage"
      confirm-text="继续"
      @cancel="cancelHistory"
      @confirm="runHistory"
    />
    <ConfirmDialog
      :open="withdrawOpen"
      title="下架当前目录"
      message="下架后小程序不再展示当前目录。旧版本不会自动变回当前内容。"
      confirm-text="确认下架"
      danger
      @cancel="withdrawOpen = false"
      @confirm="
        withdrawOpen = false;
        store.withdraw();
      "
    />
  </div>
</template>
