<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { authStoreKey } from "../auth/store";
import { formatDateTime } from "../content/format";
import { AdminUserApi, UserApiError, type ShareChain, type UserDetail } from "../users/api";

const PAGE_LABELS: Record<string, string> = {
  "pages/index/index": "首页",
  "pages/works/works": "作品",
  "pages/works/workdetail": "作品详情",
  "pages/package/package": "套餐",
  "pages/package/pkgdetail": "套餐详情",
  "pages/about/about": "关于",
  "pages/booking/booking": "预约",
  "pages/booking/requestdetail": "预约详情",
  "pages/mine/mine": "我的",
};

const api = new AdminUserApi();
const auth = inject(authStoreKey);
const route = useRoute();
const router = useRouter();
const canWrite = computed(
  () => auth?.state.identity?.permissions.includes("USER_PROFILE_WRITE") ?? false,
);
const detail = ref<UserDetail | null>(null);
const etag = ref("");
const name = ref("");
const note = ref("");
const notice = ref("");
const chain = ref<ShareChain | null>(null);
const loading = ref(true);

function pageLabel(page: string) {
  return PAGE_LABELS[page] ?? page;
}

async function load() {
  loading.value = true;
  notice.value = "";
  chain.value = null;
  try {
    const result = await api.detail(String(route.params.userId));
    detail.value = result.user;
    etag.value = result.etag;
    name.value = result.user.user.displayName ?? "";
    note.value = result.user.note ?? "";
  } catch (error) {
    notice.value = error instanceof UserApiError ? error.message : "用户资料暂时无法读取。";
  } finally {
    loading.value = false;
  }
}

async function save() {
  if (!detail.value) return;
  notice.value = "";
  try {
    const result = await api.save(detail.value.user.id, etag.value, name.value, note.value);
    detail.value = result.user;
    etag.value = result.etag;
    notice.value = "已保存。";
  } catch (error) {
    notice.value = error instanceof UserApiError ? error.message : "没有保存成功。";
    if (error instanceof UserApiError && error.status === 409) await load();
  }
}

async function openShare(id: string) {
  notice.value = "";
  try {
    chain.value = await api.share(id);
  } catch (error) {
    notice.value = error instanceof UserApiError ? error.message : "传播路径暂时无法读取。";
  }
}

function hideImage(event: Event) {
  const image = event.target;
  if (image instanceof HTMLImageElement) image.style.display = "none";
}

function openReservation(id: string) {
  void router.push({ name: "bookings", query: { reservation: id } });
}

onMounted(() => void load());
</script>

<template>
  <p v-if="loading">正在读取。</p>
  <p v-else-if="!detail">{{ notice || "没有找到这位用户。" }}</p>
  <section v-else class="card">
    <p>
      <img
        class="user-avatar"
        :src="`/api/admin/v1/users/${detail.user.id}/avatar`"
        alt=""
        @error="hideImage"
      />
      {{ detail.user.displayLabel }}
      <span>短号 {{ detail.user.shortId }}</span>
    </p>
    <p class="hint">
      创建于 {{ formatDateTime(detail.createdAt) }}，最近进入
      {{ formatDateTime(detail.user.lastSeenAt) }}。打开 {{ detail.visitCount }} 次，分享
      {{ detail.shareCount }} 次，转发 {{ detail.forwardCount }} 次。
    </p>
    <form class="field" @submit.prevent="save">
      <label for="user-name">昵称</label>
      <input id="user-name" v-model="name" maxlength="32" :disabled="!canWrite" />
      <label for="user-note">备注</label>
      <textarea id="user-note" v-model="note" maxlength="500" rows="4" :disabled="!canWrite" />
      <button v-if="canWrite" class="btn" type="submit">保存</button>
    </form>
    <p v-if="notice">{{ notice }}</p>
    <h2>预约</h2>
    <p v-if="detail.reservations.length === 0" class="hint">还没有和这位用户对上的预约。</p>
    <ul v-else>
      <li v-for="item in detail.reservations" :key="item.id">
        <button type="button" class="btn" @click="openReservation(item.id)">
          {{
            item.localDate ? `${item.localDate} ${item.localStart}-${item.localEnd}` : "未指定时段"
          }}
          {{ item.status }}
        </button>
      </li>
    </ul>
    <h2>访问足迹</h2>
    <p v-if="detail.visits.length === 0" class="hint">还没有访问记录。</p>
    <table v-else class="table">
      <thead>
        <tr>
          <th>时间</th>
          <th>页面</th>
          <th>内容</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="visit in detail.visits" :key="visit.id">
          <td>{{ formatDateTime(visit.occurredAt) }}</td>
          <td>{{ pageLabel(visit.page) }}</td>
          <td>{{ visit.contentId || "—" }}</td>
          <td>
            <button
              v-if="visit.shareCardId"
              type="button"
              class="btn"
              @click="openShare(visit.shareCardId)"
            >
              传播路径
            </button>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-if="chain">
      <h2>这条分享</h2>
      <p>{{ pageLabel(chain.page) }} {{ chain.contentId || "" }}</p>
      <p>路径上有 {{ chain.chain.length }} 次转发。</p>
      <h3>打开的人</h3>
      <ul>
        <li v-for="person in chain.opens" :key="person.userId + person.occurredAt">
          {{ person.displayLabel }}（{{ person.shortId }}）{{ formatDateTime(person.occurredAt) }}
        </li>
      </ul>
      <p v-if="chain.opens.length === 0" class="hint">还没有人从这张卡片打开。</p>
      <h3>继续分享的人</h3>
      <ul>
        <li v-for="person in chain.forwards" :key="person.userId + person.occurredAt">
          <button
            type="button"
            class="btn"
            @click="router.push({ name: 'user-detail', params: { userId: person.userId } })"
          >
            {{ person.displayLabel }}（{{ person.shortId }}）
          </button>
        </li>
      </ul>
      <p v-if="chain.forwards.length === 0" class="hint">还没有人继续分享。</p>
    </div>
  </section>
</template>

<style scoped>
.user-avatar {
  width: 72px;
  height: 72px;
  object-fit: cover;
  border-radius: 50%;
  vertical-align: middle;
  margin-right: 12px;
  background: #f4efea;
}
textarea {
  width: 100%;
  max-width: 36rem;
}
</style>
