<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { authStoreKey } from "../auth/store";
import {
  AdminOutboxApi,
  newOutboxIdempotencyKey,
  OutboxApiError,
  type OutboxBoard,
} from "../outbox/api";
import StatusChip from "../components/StatusChip.vue";
import { failureLabel, formatDateTime, notificationIntentLabel } from "../content/format";

const api = new AdminOutboxApi();
const auth = inject(authStoreKey);
const board = ref<OutboxBoard | null>(null);
const loading = ref(true);
const busyId = ref("");
const notice = ref("");
const requestId = ref("");
const unknownIds = ref(new Set<string>());
const retryKeys = new Map<string, string>();
const canRead = computed(() => auth?.state.identity?.permissions.includes("OUTBOX_READ") ?? false);
const canRecover = computed(
  () => auth?.state.identity?.permissions.includes("OUTBOX_RECOVER") ?? false,
);

async function load(preserveNotice = false) {
  if (!canRead.value) {
    loading.value = false;
    board.value = null;
    return;
  }
  loading.value = true;
  if (!preserveNotice) {
    notice.value = "";
    requestId.value = "";
  }
  try {
    board.value = await api.list();
    for (const id of [...unknownIds.value]) {
      if (!board.value.failures.some((failure) => failure.id === id)) unknownIds.value.delete(id);
    }
  } catch (error) {
    board.value = null;
    report(error);
  } finally {
    loading.value = false;
  }
}

function report(error: unknown) {
  const failure = error instanceof OutboxApiError ? error : null;
  requestId.value = failure?.requestId ?? "";
  if (failure?.outcomeUnknown) {
    notice.value = "还不确定有没有重新发送。请先刷新列表再试。";
  } else if (failure?.status === 403 && failure.code === "CAPABILITY_REQUIRED") {
    notice.value = "当前账号不能查看或重发这些消息。";
  } else if (failure?.status === 409 && failure.code === "OUTBOX_NOT_RECOVERABLE") {
    notice.value = "这条消息已经处理过了，请刷新看看。";
  } else {
    notice.value = failure?.message ?? "暂时无法完成请求，请检查连接后重试。";
  }
}

async function recover(id: string) {
  if (!canRecover.value || busyId.value) return;
  const confirmed = window.confirm("再试一次把这条消息发出去？");
  if (!confirmed) return;
  busyId.value = id;
  notice.value = "";
  const key = retryKeys.get(id) ?? newOutboxIdempotencyKey();
  retryKeys.set(id, key);
  try {
    const result = await api.recover(id, key);
    retryKeys.delete(id);
    unknownIds.value.delete(id);
    notice.value = result.replayed ? "已确认上次的发送请求，正在刷新。" : "已安排重新发送。";
    await load(true);
  } catch (error) {
    if (error instanceof OutboxApiError && error.outcomeUnknown) unknownIds.value.add(id);
    report(error);
    if (error instanceof OutboxApiError && error.outcomeUnknown) await load(true);
  } finally {
    busyId.value = "";
  }
}

onMounted(() => void load());
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>消息通知</h1>
        <p>没发出去的消息会列在这里，可以再试一次。</p>
      </div>
      <div class="actions">
        <button class="btn btn-secondary btn-sm" type="button" :disabled="loading" @click="load()">
          {{ loading ? "正在刷新…" : "刷新列表" }}
        </button>
      </div>
    </div>
    <div v-if="board" class="note" style="margin-bottom: 18px" role="status">
      <b>说明</b>
      <span>还有 {{ board.pendingCount }} 条在等待发送。</span>
    </div>
    <p v-if="notice" class="alert-line" role="alert">
      {{ notice }}
    </p>
    <p v-if="!canRead">当前账号不能查看消息。</p>
    <p v-else-if="loading">正在读取失败通知…</p>
    <p v-else-if="board && board.failures.length === 0">目前没有发送失败的消息。</p>
    <div v-else-if="board" class="card" style="overflow: hidden">
      <table class="table">
        <thead>
          <tr>
            <th>内容</th>
            <th>尝试次数</th>
            <th>最后错误</th>
            <th>时间</th>
            <th>状态</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="failure in board.failures" :key="failure.id" :data-message-id="failure.id">
            <td>
              <b>{{ notificationIntentLabel(failure.intent) }}</b>
            </td>
            <td>{{ failure.attempts }}</td>
            <td>{{ failureLabel(failure.lastError) }}</td>
            <td>
              <time :datetime="failure.dueAt">{{ formatDateTime(failure.dueAt) }}</time>
            </td>
            <td><StatusChip tone="warn" text="发送失败" /></td>
            <td style="text-align: right">
              <button
                v-if="canRecover"
                class="btn btn-secondary btn-sm recover-button"
                type="button"
                :disabled="busyId !== ''"
                :aria-label="'再试一次'"
                @click="recover(failure.id)"
              >
                {{ busyId === failure.id ? "处理中…" : "再试一次" }}
              </button>
              <span v-else>当前账号不能重发这些消息。</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
