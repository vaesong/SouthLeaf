<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { authStoreKey } from "../auth/store";
import { formatClock, formatDate, formatSeen, reservationStatusLabel } from "../content/format";
import {
  AdminUserApi,
  UserApiError,
  type ShareChain,
  type SharePerson,
  type UserDetail,
  type UserReservation,
} from "../users/api";

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
const chainId = ref("");
const loading = ref(true);

const path = computed(() => [...(detail.value?.visits ?? [])].reverse());

function pageLabel(page: string) {
  return PAGE_LABELS[page] ?? page;
}

function guestName(label: string, shortId: string) {
  return label === `微信用户 ${shortId}` ? "还没有昵称" : label;
}

function personName(person: SharePerson) {
  return guestName(person.displayLabel, person.shortId);
}

function userName() {
  const current = detail.value?.user;
  if (!current?.named || !current.displayName) return "还没有昵称";
  return current.displayName;
}

function initial() {
  const current = detail.value?.user;
  if (!current?.named || !current.displayName) return "微";
  return current.displayName.slice(0, 1);
}

function slotLabel(item: UserReservation) {
  if (!item.localDate || !item.localStart || !item.localEnd) return "未指定时段";
  return `${item.localDate} ${item.localStart}–${item.localEnd}`;
}

function tone(status: string) {
  if (status === "PENDING_CONFIRMATION") return "warn";
  if (status === "CONFIRMED") return "live";
  return "off";
}

async function load() {
  loading.value = true;
  notice.value = "";
  chain.value = null;
  chainId.value = "";
  try {
    const result = await api.detail(String(route.params.userId));
    detail.value = result.user;
    etag.value = result.etag;
    name.value = result.user.user.displayName ?? "";
    note.value = result.user.note ?? "";
  } catch (error) {
    detail.value = null;
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
    name.value = result.user.user.displayName ?? "";
    note.value = result.user.note ?? "";
    notice.value = "已保存。";
  } catch (error) {
    notice.value = error instanceof UserApiError ? error.message : "没有保存成功。";
    if (error instanceof UserApiError && error.status === 409) await load();
  }
}

async function openShare(id: string) {
  if (chainId.value === id) {
    chain.value = null;
    chainId.value = "";
    return;
  }
  notice.value = "";
  try {
    chain.value = await api.share(id);
    chainId.value = id;
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

function openUser(id: string) {
  void router.push({ name: "user-detail", params: { userId: id } });
}

onMounted(() => void load());
watch(
  () => route.params.userId,
  () => void load(),
);
</script>

<template>
  <p v-if="loading">正在读取。</p>
  <p v-else-if="!detail">{{ notice || "没有找到这位用户。" }}</p>
  <div v-else>
    <button class="back" type="button" @click="router.push({ name: 'users' })">‹ 返回用户</button>
    <section class="hero">
      <span class="code">{{ detail.user.shortId }}</span>
      <div class="hero-top">
        <span class="face" aria-hidden="true">
          {{ initial() }}
          <img :src="`/api/admin/v1/users/${detail.user.id}/avatar`" alt="" @error="hideImage" />
        </span>
        <div>
          <h2 :class="{ quiet: !detail.user.named }">{{ userName() }}</h2>
          <p class="when">
            {{ formatDate(detail.createdAt) }} 第一次来 ·
            {{ formatSeen(detail.user.lastSeenAt) }} 还在
          </p>
        </div>
      </div>
      <div class="meters">
        <div class="meter">
          <b>{{ detail.visitCount }}</b
          ><span>次打开</span>
        </div>
        <div class="meter">
          <b>{{ detail.shareCount }}</b
          ><span>次分享</span>
        </div>
        <div class="meter">
          <b>{{ detail.forwardCount }}</b
          ><span>次转发</span>
        </div>
      </div>
    </section>
    <section class="card route">
      <div class="card-head route-head">
        <h3>走过的页面</h3>
        <p>从左到右是时间顺序。实心点是从分享进来的。</p>
      </div>
      <div class="pad">
        <p v-if="path.length === 0" class="empty-line">还没有访问记录。</p>
        <div v-else class="stations">
          <button
            v-for="visit in path"
            :key="visit.id"
            class="station"
            :class="{ share: visit.shareCardId }"
            type="button"
            :disabled="!visit.shareCardId"
            @click="visit.shareCardId && openShare(visit.shareCardId)"
          >
            <i></i>
            <time>{{ formatClock(visit.occurredAt) }}</time>
            <b>{{ pageLabel(visit.page) }}</b>
            <small>{{ visit.shareCardId ? "从分享进来 · 看路径" : "打开" }}</small>
          </button>
        </div>
        <div v-if="chain" class="flow">
          <template v-for="(hop, index) in chain.chain" :key="hop.id">
            <span v-if="index > 0" class="arrow">→</span>
            <div class="node">
              <small>发出</small>
              <b>{{ hop.name }}</b>
              <small>{{ pageLabel(hop.page) }}</small>
            </div>
          </template>
          <template v-for="person in chain.opens" :key="person.userId + person.occurredAt">
            <span class="arrow">→</span>
            <button class="node link" type="button" @click="openUser(person.userId)">
              <small>打开</small>
              <b>{{ personName(person) }}</b>
              <small>{{ formatSeen(person.occurredAt) }}</small>
            </button>
          </template>
          <template v-for="person in chain.forwards" :key="'f' + person.userId + person.occurredAt">
            <span class="arrow">→</span>
            <button class="node link" type="button" @click="openUser(person.userId)">
              <small>转发</small>
              <b>{{ personName(person) }}</b>
              <small>{{ formatSeen(person.occurredAt) }}</small>
            </button>
          </template>
        </div>
      </div>
    </section>
    <div class="two">
      <section class="card">
        <div class="card-head"><h3>预约</h3></div>
        <div class="pad">
          <p v-if="detail.reservations.length === 0" class="empty-line">
            还没有和这位用户对上的预约。
          </p>
          <button
            v-for="item in detail.reservations"
            :key="item.id"
            class="booking"
            type="button"
            @click="openReservation(item.id)"
          >
            <span
              ><b>{{ slotLabel(item) }}</b></span
            >
            <span class="chip" :class="tone(item.status)"
              ><i></i>{{ reservationStatusLabel(item.status) }}</span
            >
          </button>
        </div>
      </section>
      <section class="card">
        <div class="card-head"><h3>用户名</h3></div>
        <form class="pad" @submit.prevent="save">
          <div class="field">
            <label for="username">用户名</label>
            <input
              id="username"
              v-model="name"
              class="input"
              maxlength="32"
              placeholder="还没有昵称"
              :disabled="!canWrite"
            />
          </div>
          <div class="field">
            <label for="user-note">备注</label>
            <textarea
              id="user-note"
              v-model="note"
              class="textarea"
              maxlength="500"
              rows="4"
              placeholder="只在后台可见"
              :disabled="!canWrite"
            />
          </div>
          <div>
            <button v-if="canWrite" class="btn btn-primary" type="submit">保存</button>
          </div>
          <p v-if="notice" class="hint">{{ notice }}</p>
        </form>
      </section>
    </div>
  </div>
</template>

<style scoped>
.back {
  display: inline-flex;
  margin-bottom: 16px;
  border: 0;
  background: none;
  color: var(--ink-2);
  padding: 0;
  cursor: pointer;
}
.hero {
  position: relative;
  overflow: hidden;
  padding: 28px 28px 22px;
  border-radius: 22px;
  color: #fff6ec;
  background:
    radial-gradient(circle at 92% -10%, rgba(243, 194, 122, 0.55), transparent 34%),
    linear-gradient(145deg, #2c261f 0%, #4a3428 68%, #6d3b4a 100%);
}
.hero-top {
  display: flex;
  align-items: center;
  gap: 18px;
}
.hero h2 {
  font-family: var(--serif);
  font-size: 40px;
  font-weight: 500;
  line-height: 1.1;
}
.hero h2.quiet {
  color: rgba(255, 246, 236, 0.72);
}
.when {
  margin-top: 8px;
  color: rgba(255, 246, 236, 0.72);
}
.code {
  position: absolute;
  top: 16px;
  right: 18px;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  letter-spacing: 0.08em;
  color: rgba(255, 246, 236, 0.38);
}
.face {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 50%;
  overflow: hidden;
  flex: none;
  display: grid;
  place-items: center;
  background: #efe4d8;
  color: var(--accent-2);
  font-family: var(--serif);
  font-size: 28px;
}
.face img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.meters {
  display: flex;
  margin-top: 22px;
  border-top: 1px solid rgba(255, 246, 236, 0.14);
}
.meter {
  flex: 1;
  min-width: 0;
  padding: 14px 16px 0 0;
}
.meter + .meter {
  padding-left: 16px;
  border-left: 1px solid rgba(255, 246, 236, 0.14);
}
.meter b {
  display: block;
  font-family: var(--serif);
  font-size: 32px;
  line-height: 1;
}
.meter span {
  font-size: 12px;
  color: rgba(255, 246, 236, 0.68);
}
.route {
  margin-top: 16px;
}
.route-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}
.route-head p,
.empty-line {
  color: var(--ink-3);
  font-size: 12px;
}
.pad {
  padding: 16px 22px 22px;
}
.stations {
  display: flex;
  overflow-x: auto;
  padding-bottom: 8px;
}
.station {
  position: relative;
  min-width: 132px;
  flex: 1;
  padding: 22px 16px 0 0;
  text-align: left;
  background: none;
  border: 0;
  cursor: pointer;
}
.station:disabled {
  cursor: default;
  opacity: 1;
}
.station::before {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  top: 5px;
  height: 2px;
  background: linear-gradient(90deg, var(--accent), #e7c7a4);
}
.station i {
  position: absolute;
  left: 0;
  top: 0;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--card);
  border: 2px solid var(--accent);
  box-shadow: 0 0 0 4px #fff;
}
.station.share i {
  background: var(--accent);
}
.station b,
.station small {
  display: block;
}
.station small {
  color: var(--ink-3);
}
.flow {
  display: flex;
  align-items: stretch;
  gap: 10px;
  margin-top: 16px;
  flex-wrap: wrap;
}
.node {
  flex: 1;
  min-width: 140px;
  padding: 14px 16px;
  border-radius: 16px;
  background: var(--sunk);
  border: 1px solid var(--line);
  text-align: left;
}
.node.link {
  cursor: pointer;
}
.node b {
  display: block;
  font-family: var(--serif);
  font-size: 18px;
}
.node small {
  color: var(--ink-3);
}
.arrow {
  align-self: center;
  color: var(--accent);
  font-size: 20px;
}
.two {
  display: grid;
  grid-template-columns: 1.2fr 0.8fr;
  gap: 16px;
  margin-top: 16px;
}
.booking {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 12px 0;
  border: 0;
  border-top: 1px solid var(--line-2);
  background: none;
  text-align: left;
  cursor: pointer;
}
.booking b,
.booking small {
  display: block;
}
.booking small {
  color: var(--ink-3);
}
.chip {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  flex: none;
}
.chip i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.chip.warn {
  background: var(--gold-soft);
  color: #86641f;
}
.chip.live {
  background: var(--sage-soft);
  color: var(--sage);
}
.chip.off {
  background: #eee8df;
  color: var(--ink-3);
}
.textarea {
  min-height: 96px;
  resize: vertical;
}
@media (max-width: 900px) {
  .two {
    grid-template-columns: 1fr;
  }
  .hero h2 {
    font-size: 32px;
  }
}
</style>
