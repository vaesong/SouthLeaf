<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { authStoreKey } from "../auth/store";
import { formatSeen } from "../content/format";
import { AdminUserApi, UserApiError, type UserCensus, type UserSummary } from "../users/api";

const api = new AdminUserApi();
const auth = inject(authStoreKey);
const router = useRouter();
const canRead = computed(() => auth?.state.identity?.permissions.includes("USER_READ") ?? false);
const users = ref<UserSummary[]>([]);
const census = ref<UserCensus>({
  total: 0,
  seenToday: 0,
  unnamed: 0,
  withReservation: 0,
  shared: 0,
});
const query = ref("");
const cursor = ref<string | null>(null);
const loading = ref(true);
const notice = ref("");

const counts = computed(() => [
  { label: "全部用户", value: census.value.total },
  { label: "今日进入", value: census.value.seenToday },
  { label: "还没有昵称", value: census.value.unnamed },
  { label: "有预约", value: census.value.withReservation },
  { label: "分享过", value: census.value.shared },
]);

function userName(user: UserSummary) {
  return user.named && user.displayName ? user.displayName : "还没有昵称";
}

function initial(user: UserSummary) {
  return user.named && user.displayName ? user.displayName.slice(0, 1) : "微";
}

async function load(reset: boolean) {
  if (!canRead.value) {
    loading.value = false;
    return;
  }
  loading.value = true;
  notice.value = "";
  try {
    const page = await api.list(query.value.trim(), reset ? "" : (cursor.value ?? ""));
    users.value = reset ? page.users : users.value.concat(page.users);
    cursor.value = page.nextCursor;
    census.value = page.census;
  } catch (error) {
    notice.value = error instanceof UserApiError ? error.message : "用户列表暂时无法读取。";
  } finally {
    loading.value = false;
  }
}

function open(id: string) {
  void router.push({ name: "user-detail", params: { userId: id } });
}

function hideImage(event: Event) {
  const image = event.target;
  if (image instanceof HTMLImageElement) image.style.display = "none";
}

onMounted(() => void load(true));
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>用户</h1>
        <p>进入小程序就会登录。上面是全部用户的汇总，人多了也先看这几个数。</p>
      </div>
    </div>
    <p v-if="!canRead" class="hint">当前账号不能查看用户。</p>
    <template v-else>
      <div class="summary">
        <article v-for="item in counts" :key="item.label">
          <b>{{ item.value }}</b>
          <span>{{ item.label }}</span>
        </article>
      </div>
      <form class="toolbar" @submit.prevent="load(true)">
        <label class="search">
          <input v-model="query" maxlength="32" placeholder="搜索用户名" />
        </label>
      </form>
      <p v-if="notice" class="alert-line" role="alert">{{ notice }}</p>
      <p v-else-if="loading && users.length === 0" class="hint">正在读取。</p>
      <p v-else-if="users.length === 0" class="hint">
        {{ query.trim() ? "没有找到这个用户名。" : "还没有进入过小程序的用户。" }}
      </p>
      <section v-if="users.length" class="card people">
        <button
          v-for="user in users"
          :key="user.id"
          class="person"
          type="button"
          @click="open(user.id)"
        >
          <span class="face" aria-hidden="true">
            {{ initial(user) }}
            <img :src="`/api/admin/v1/users/${user.id}/avatar`" alt="" @error="hideImage" />
          </span>
          <span>
            <b :class="{ quiet: !user.named }">{{ userName(user) }}</b>
            <small>最近进入 {{ formatSeen(user.lastSeenAt) }}</small>
          </span>
          <span class="go">›</span>
        </button>
      </section>
      <button
        v-if="cursor"
        class="btn btn-secondary more"
        type="button"
        :disabled="loading"
        @click="load(false)"
      >
        {{ loading ? "正在读取…" : "继续加载" }}
      </button>
    </template>
  </div>
</template>

<style scoped>
.summary {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}
.summary article {
  flex: 1;
  min-width: 0;
  padding: 14px 16px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 16px;
  box-shadow: var(--shadow);
}
.summary b {
  display: block;
  font-family: var(--serif);
  font-size: 26px;
  line-height: 1.1;
}
.summary span {
  color: var(--ink-3);
  font-size: 12px;
}
.people {
  overflow: hidden;
}
.person {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  padding: 16px 22px;
  border: 0;
  border-bottom: 1px solid var(--line-2);
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.person:last-child {
  border-bottom: 0;
}
.person:hover {
  background: #fffbf6;
}
.person b {
  display: block;
  font-size: 15px;
  font-weight: 600;
}
.person b.quiet {
  color: var(--ink-3);
  font-weight: 500;
}
.person small {
  color: var(--ink-3);
  font-size: 12px;
}
.face {
  position: relative;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  overflow: hidden;
  flex: none;
  display: grid;
  place-items: center;
  background: #efe4d8;
  color: var(--accent-2);
  font-weight: 600;
}
.face img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.go {
  margin-left: auto;
  color: var(--ink-3);
  font-size: 18px;
}
.more {
  margin-top: 16px;
}
@media (max-width: 800px) {
  .summary {
    flex-wrap: wrap;
  }
  .summary article {
    flex: 1 1 120px;
  }
}
</style>
