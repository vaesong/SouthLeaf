<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { authStoreKey } from "../auth/store";
import { formatDateTime } from "../content/format";
import { AdminUserApi, UserApiError, type UserSummary } from "../users/api";

const api = new AdminUserApi();
const auth = inject(authStoreKey);
const router = useRouter();
const canRead = computed(() => auth?.state.identity?.permissions.includes("USER_READ") ?? false);
const users = ref<UserSummary[]>([]);
const query = ref("");
const cursor = ref<string | null>(null);
const loading = ref(true);
const notice = ref("");

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
  if (image instanceof HTMLImageElement) image.style.visibility = "hidden";
}

onMounted(() => void load(true));
</script>

<template>
  <section class="card">
    <form class="field" @submit.prevent="load(true)">
      <label for="user-query">搜索昵称或短号</label>
      <input id="user-query" v-model="query" maxlength="32" placeholder="昵称或 6 位短号" />
      <button class="btn" type="submit">搜索</button>
    </form>
    <p v-if="notice" class="hint">{{ notice }}</p>
    <p v-else-if="!loading && users.length === 0" class="hint">还没有进入过小程序的用户。</p>
    <table v-if="users.length" class="table">
      <thead>
        <tr>
          <th>用户</th>
          <th>短号</th>
          <th>最近进入</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="user in users" :key="user.id" @click="open(user.id)">
          <td>
            <img
              class="user-avatar"
              :src="`/api/admin/v1/users/${user.id}/avatar`"
              alt=""
              @error="hideImage"
            />
            {{ user.displayLabel }}
          </td>
          <td>{{ user.shortId }}</td>
          <td>{{ formatDateTime(user.lastSeenAt) }}</td>
        </tr>
      </tbody>
    </table>
    <button v-if="cursor" class="btn" type="button" :disabled="loading" @click="load(false)">
      继续加载
    </button>
  </section>
</template>

<style scoped>
.user-avatar {
  width: 36px;
  height: 36px;
  object-fit: cover;
  border-radius: 50%;
  vertical-align: middle;
  margin-right: 8px;
  background: #f4efea;
}
tr {
  cursor: pointer;
}
</style>
