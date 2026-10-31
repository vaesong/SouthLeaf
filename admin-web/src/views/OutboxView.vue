<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { authStoreKey } from "../auth/store";
import {
  AdminOutboxApi,
  newOutboxIdempotencyKey,
  OutboxApiError,
  type OutboxBoard,
} from "../outbox/api";

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
    notice.value = "恢复请求结果尚未确认。请先刷新列表核对；不要另行创建重复操作。";
  } else if (failure?.status === 403 && failure.code === "CAPABILITY_REQUIRED") {
    notice.value = "当前会话没有查看或恢复通知待办的服务器能力。";
  } else if (failure?.status === 409 && failure.code === "OUTBOX_NOT_RECOVERABLE") {
    notice.value = "该通知已被其他工作进程处理或已不再处于失败状态；请刷新核对。";
  } else {
    notice.value = failure?.message ?? "暂时无法完成请求，请检查连接后重试。";
  }
}

async function recover(id: string) {
  if (!canRecover.value || busyId.value) return;
  const confirmed = window.confirm(
    "仅将此失败任务重新排队一次；自动尝试次数不会清零。当前 TEST_SINK 只记录本地接受状态，不会发送真实通知。继续？",
  );
  if (!confirmed) return;
  busyId.value = id;
  notice.value = "";
  const key = retryKeys.get(id) ?? newOutboxIdempotencyKey();
  retryKeys.set(id, key);
  try {
    const result = await api.recover(id, key);
    retryKeys.delete(id);
    unknownIds.value.delete(id);
    notice.value = result.replayed
      ? "已核对到同一恢复命令；正在刷新待办状态。"
      : "恢复命令已记账并重新排队；测试接收器不会对外发送通知。";
    await load(true);
  } catch (error) {
    if (error instanceof OutboxApiError && error.outcomeUnknown) unknownIds.value.add(id);
    report(error);
    if (error instanceof OutboxApiError && error.outcomeUnknown) await load(true);
  } finally {
    busyId.value = "";
  }
}

function utc(value: string) {
  return value;
}

onMounted(() => void load());
</script>

<template>
  <main class="identity-shell outbox-shell">
    <header class="topbar">
      <RouterLink class="wordmark" to="/" aria-label="SouthLeaf 管理首页">
        <span class="wordmark-mark">S</span>
        <span>SouthLeaf <small>ADMIN</small></span>
      </RouterLink>
      <div class="topbar-status">
        <span class="environment-badge">S6 · 本地补偿工作台</span>
        <span class="secure-label"><span class="secure-dot"></span>安全工作阶段</span>
      </div>
    </header>

    <section class="outbox-card" aria-labelledby="outbox-title">
      <div class="page-heading">
        <div>
          <p class="eyebrow">NOTIFICATION OPERATIONS</p>
          <h1 id="outbox-title">通知失败待办</h1>
          <p class="panel-copy">
            只显示安全错误码与队列状态，不显示客户标识、联系方式或 provider 原始错误。
          </p>
        </div>
        <button class="secondary-button" type="button" :disabled="loading" @click="load()">
          {{ loading ? "正在刷新…" : "刷新列表" }}
        </button>
      </div>

      <div v-if="board" class="test-sink-banner" role="status">
        <strong>测试接收器 TEST_SINK</strong>
        <span>当前仅在本地记账；不会发送真实通知。</span>
        <span>到期待派送：{{ board.pendingCount }}</span>
      </div>

      <div v-if="notice" class="outbox-notice" role="alert" aria-live="assertive">
        <span>{{ notice }}</span>
        <small v-if="requestId">Request ID: {{ requestId }}</small>
      </div>

      <div v-if="!canRead" class="empty-state" role="status">
        当前会话没有查看能力；服务器仍会逐请求检查权限。
      </div>
      <div v-else-if="loading" class="empty-state" role="status">正在读取失败待办…</div>
      <div v-else-if="board && board.failures.length === 0" class="empty-state" role="status">
        目前没有需要人工恢复的失败通知。
      </div>
      <div v-else-if="board" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">任务 ID</th>
              <th scope="col">业务事件</th>
              <th scope="col">已尝试次数</th>
              <th scope="col">最后错误码</th>
              <th scope="col">下次处理时刻（UTC）</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="failure in board.failures" :key="failure.id">
              <td>
                <code>{{ failure.id }}</code>
              </td>
              <td>{{ failure.intent }}</td>
              <td>{{ failure.attempts }}</td>
              <td>
                <code>{{ failure.lastError }}</code>
              </td>
              <td>
                <time :datetime="failure.dueAt">{{ utc(failure.dueAt) }}</time>
              </td>
              <td>
                <button
                  v-if="canRecover"
                  class="recover-button"
                  type="button"
                  :disabled="busyId !== ''"
                  :aria-label="`人工恢复通知 ${failure.id}`"
                  @click="recover(failure.id)"
                >
                  {{
                    busyId === failure.id
                      ? "处理中…"
                      : unknownIds.has(failure.id)
                        ? "核对后重试同一命令"
                        : "人工恢复"
                  }}
                </button>
                <span v-else class="muted">缺少恢复能力</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <p class="outbox-footnote">
        恢复会保留原自动尝试计数，并与审计、幂等账本同事务提交；网络中断时先刷新核对。真实
        provider、外部送达保证与生产权限不在此本地切片内。
      </p>
      <RouterLink class="back-link" to="/">返回工作台</RouterLink>
    </section>
  </main>
</template>

<style scoped>
.outbox-shell {
  min-height: 100vh;
  padding-bottom: 48px;
}
.outbox-card {
  width: min(1180px, calc(100% - 32px));
  margin: 42px auto;
  padding: 32px;
  border: 1px solid #e7e5df;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 20px 50px #24352b12;
}
.page-heading {
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
  max-width: 720px;
  color: #647068;
  line-height: 1.65;
}
.test-sink-banner {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 22px;
  align-items: center;
  margin: 20px 0;
  padding: 14px 18px;
  border: 1px solid #e5d4a5;
  border-radius: 12px;
  background: #fff9e8;
  color: #5a4a1c;
}
.outbox-notice {
  display: grid;
  gap: 6px;
  margin: 18px 0;
  padding: 14px 16px;
  border-left: 4px solid #bd7742;
  background: #fff4ed;
  color: #673b27;
}
.outbox-notice small {
  color: #755e51;
}
.table-wrap {
  overflow-x: auto;
  margin-top: 22px;
}
table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
}
th,
td {
  padding: 14px 12px;
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
code {
  overflow-wrap: anywhere;
  color: #48564c;
  font-size: 12px;
}
.empty-state {
  margin: 20px 0;
  padding: 30px;
  border-radius: 12px;
  background: #f6f7f3;
  color: #647068;
  text-align: center;
}
.secondary-button,
.recover-button {
  min-height: 40px;
  padding: 9px 14px;
  border: 1px solid #355647;
  border-radius: 9px;
  background: #fff;
  color: #294738;
  font-weight: 700;
  cursor: pointer;
}
.recover-button {
  border-color: #976338;
  color: #744824;
  white-space: nowrap;
}
button:disabled {
  opacity: 0.55;
  cursor: wait;
}
.muted {
  color: #8b938d;
  font-size: 13px;
}
.outbox-footnote {
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
  .outbox-card {
    padding: 20px;
  }
  .page-heading {
    flex-direction: column;
  }
  th,
  td {
    padding: 12px 8px;
  }
}
</style>
