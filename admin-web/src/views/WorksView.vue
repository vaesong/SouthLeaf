<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { authStoreKey } from "../auth/store";
import AppIcon from "../components/AppIcon.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import MediaThumb from "../components/MediaThumb.vue";
import StatusChip from "../components/StatusChip.vue";
import { newIdempotencyKey, type WorkView } from "../content/api";
import { catalogStoreKey } from "../content/catalogStore";
import { businessNameLabel, formatDate, workStateLabel } from "../content/format";
import { requireContentApi } from "../content/inject";
import { describeFailure } from "../content/messages";
import WorkEditView from "./WorkEditView.vue";

const api = requireContentApi();
const auth = inject(authStoreKey);
const catalog = inject(catalogStoreKey);
const router = useRouter();
const route = useRoute();
const works = ref<WorkView[]>([]);
const loading = ref(true);
const busy = ref(false);
const notice = ref("");
const requestId = ref("");
const filter = ref("all");
const category = ref("all");
const keyword = ref("");
const orders = ref<Record<string, string[]>>({});
const orderNotice = ref("");
const dragging = ref("");
const dropTarget = ref("");
const pointer = ref<{ id: string; x: number; y: number; moved: boolean } | null>(null);
let suppressNav = false;
let orderGeneration = 0;
const deleteTarget = ref<WorkView | null>(null);
let createKey: string | null = null;
const canEdit = computed(() => auth?.state.identity?.permissions.includes("CONTENT_EDIT") ?? false);

function label(state: string) {
  return workStateLabel(state);
}

function businessLine(work: WorkView) {
  const names = work.businessIds.map((id) => {
    const named = catalog?.state.draft?.businesses.find((item) => item.id === id)?.name;
    return named || businessNameLabel(id);
  });
  return names.filter((name) => name && name !== "其他").join("、") || "未分类";
}

function unpublishedEdit(work: WorkView) {
  return (
    work.publicationState === "PUBLISHED" &&
    work.publishedVersion !== null &&
    work.version !== work.publishedVersion
  );
}
function tone(state: string): "live" | "draft" | "off" {
  if (state === "PUBLISHED") return "live";
  if (state === "WITHDRAWN") return "off";
  return "draft";
}

const businesses = computed(() => catalog?.state.draft?.businesses ?? []);

function bySavedOrder(items: WorkView[]) {
  const saved = orders.value[category.value] ?? orders.value.all ?? [];
  const rank = new Map(saved.map((id, index) => [id, index]));
  return [...items].sort((left, right) => {
    const leftRank = rank.has(left.workId) ? rank.get(left.workId)! : Number.MAX_SAFE_INTEGER;
    const rightRank = rank.has(right.workId) ? rank.get(right.workId)! : Number.MAX_SAFE_INTEGER;
    if (leftRank !== rightRank) return leftRank - rightRank;
    return right.updatedAt.localeCompare(left.updatedAt);
  });
}

const inCategory = computed(() =>
  bySavedOrder(
    works.value.filter(
      (work) => category.value === "all" || work.businessIds.includes(category.value),
    ),
  ),
);

const visible = computed(() =>
  inCategory.value.filter((work) => {
    if (filter.value === "PUBLISHED" && work.publicationState !== "PUBLISHED") return false;
    if (filter.value === "WITHDRAWN" && work.publicationState !== "WITHDRAWN") return false;
    if (
      filter.value === "UNPUBLISHED" &&
      (work.publicationState === "PUBLISHED" || work.publicationState === "WITHDRAWN")
    )
      return false;
    if (keyword.value && !work.title.includes(keyword.value)) return false;
    return true;
  }),
);

const canReorder = computed(
  () => canEdit.value && filter.value === "all" && keyword.value.trim() === "" && !busy.value,
);

async function load() {
  loading.value = true;
  notice.value = "";
  try {
    const [listed, stored] = await Promise.all([api.listWorks(), api.workOrders()]);
    works.value = listed;
    orders.value = stored.scopes ?? {};
  } catch (error) {
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
  } finally {
    loading.value = false;
  }
}

async function persistOrder(ids: string[]) {
  const scope = category.value;
  const token = ++orderGeneration;
  orders.value = { ...orders.value, [scope]: ids };
  orderNotice.value = "";
  try {
    const saved = await api.saveWorkOrder(scope, ids);
    if (token !== orderGeneration) return;
    orders.value = saved.scopes ?? {};
    orderNotice.value = "顺序已保存。小程序按这个顺序显示。";
  } catch (error) {
    if (token !== orderGeneration) return;
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
    await load();
  }
}

function placeWork(source: string, target: string) {
  if (!canReorder.value || source === target) return;
  const ids = inCategory.value.map((work) => work.workId);
  const from = ids.indexOf(source);
  const to = ids.indexOf(target);
  if (from < 0 || to < 0) return;
  ids.splice(from, 1);
  ids.splice(to, 0, source);
  void persistOrder(ids);
}

function cardPointerDown(workId: string, event: PointerEvent) {
  if (!canReorder.value || event.button !== 0) return;
  if ((event.target as HTMLElement | null)?.closest("button")) return;
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  pointer.value = { id: workId, x: event.clientX, y: event.clientY, moved: false };
}

function cardPointerMove(event: PointerEvent) {
  const start = pointer.value;
  if (!start || !canReorder.value) return;
  if (!start.moved) {
    const dx = event.clientX - start.x;
    const dy = event.clientY - start.y;
    if (dx * dx + dy * dy < 25) return;
    start.moved = true;
    dragging.value = start.id;
  }
  const hit = document.elementFromPoint(event.clientX, event.clientY);
  const over = hit?.closest<HTMLElement>("[data-work-id]")?.dataset.workId ?? "";
  dropTarget.value = over && over !== start.id ? over : "";
}

function cardPointerUp() {
  const start = pointer.value;
  const over = dropTarget.value;
  pointer.value = null;
  dragging.value = "";
  dropTarget.value = "";
  if (!start?.moved) return;
  suppressNav = true;
  if (over) placeWork(start.id, over);
}

function guardDragClick(event: MouseEvent) {
  if (!suppressNav) return;
  suppressNav = false;
  event.preventDefault();
  event.stopPropagation();
}

async function createWork() {
  busy.value = true;
  notice.value = "";
  createKey ??= newIdempotencyKey();
  try {
    const created = await api.createWork(createKey);
    createKey = null;
    await router.push({ name: "work-edit", params: { workId: created.work.workId } });
  } catch (error) {
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
    if (!failure.unknown) createKey = null;
  } finally {
    busy.value = false;
  }
}

async function removeWork() {
  const target = deleteTarget.value;
  deleteTarget.value = null;
  if (!target) return;
  busy.value = true;
  notice.value = "";
  try {
    await api.deleteWork(target.workId, target.version);
    if (route.params.workId === target.workId) await router.push({ name: "works" });
    await load();
  } catch (error) {
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
  } finally {
    busy.value = false;
  }
}

onMounted(() => {
  void catalog?.load();
  void load();
});

watch(
  () => route.params.workId,
  (id, previous) => {
    if (previous && !id) void load();
  },
);
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>作品集</h1>
        <p>
          每组作品单独发布。按住作品拖到新的位置，小程序就按这个顺序显示。全部和每个分类各自一份顺序。
        </p>
      </div>
      <div class="actions">
        <button
          class="btn btn-accent"
          type="button"
          data-testid="create-work"
          :disabled="!canEdit || busy"
          @click="createWork"
        >
          <AppIcon name="plus" />新建作品
        </button>
      </div>
    </div>
    <p v-if="notice" class="alert-line" role="alert" data-testid="content-notice">{{ notice }}</p>
    <p v-if="orderNotice" class="notice-line">{{ orderNotice }}</p>
    <div class="seg work-cats">
      <button type="button" :class="{ on: category === 'all' }" @click="category = 'all'">
        全部
      </button>
      <button
        v-for="business in businesses"
        :key="business.id"
        type="button"
        :class="{ on: category === business.id }"
        @click="category = business.id"
      >
        {{ business.name || "未命名分类" }}
      </button>
    </div>
    <div class="toolbar">
      <div class="seg">
        <button type="button" :class="{ on: filter === 'all' }" @click="filter = 'all'">
          不限 {{ works.length }}
        </button>
        <button type="button" :class="{ on: filter === 'PUBLISHED' }" @click="filter = 'PUBLISHED'">
          已发布
        </button>
        <button
          type="button"
          :class="{ on: filter === 'UNPUBLISHED' }"
          @click="filter = 'UNPUBLISHED'"
        >
          未发布
        </button>
        <button type="button" :class="{ on: filter === 'WITHDRAWN' }" @click="filter = 'WITHDRAWN'">
          已撤下
        </button>
      </div>
      <div class="search" style="margin-left: auto">
        <input v-model="keyword" placeholder="搜索作品标题" />
      </div>
    </div>
    <p v-if="canEdit && !canReorder && !busy" class="hint">
      要调整顺序，先看这一分类的全部作品，并清空搜索。
    </p>
    <p v-else-if="canReorder" class="hint">按住作品拖动。从左到右、从上到下就是小程序里的顺序。</p>
    <p v-if="loading">正在读取作品…</p>
    <div v-else class="works" data-testid="works-table">
      <RouterLink
        v-for="work in visible"
        :key="work.workId"
        class="card work"
        :class="{
          sortable: canReorder,
          dragging: dragging === work.workId,
          drop: dropTarget === work.workId,
        }"
        :data-work-id="work.workId"
        :to="`/works/${work.workId}`"
        @pointerdown="cardPointerDown(work.workId, $event)"
        @pointermove="cardPointerMove"
        @pointerup="cardPointerUp"
        @pointercancel="cardPointerUp"
        @click.capture="guardDragClick"
      >
        <div class="ph">
          <MediaThumb :media-id="work.coverMediaId" quiet />
          <StatusChip :tone="tone(work.publicationState)" :text="label(work.publicationState)" />
          <StatusChip v-if="unpublishedEdit(work)" tone="warn" text="有未发布修改" />
          <span class="count" :data-testid="`work-state-${work.workId}`"
            >{{ work.media.length }} 张</span
          >
        </div>
        <div class="work-body">
          <b>{{ work.title || "未命名" }}</b>
          <small>{{ businessLine(work) }} · {{ formatDate(work.updatedAt) }}更新</small>
          <button
            v-if="canEdit"
            class="btn btn-danger-ghost"
            type="button"
            :data-testid="`delete-work-${work.workId}`"
            @click.prevent.stop="deleteTarget = work"
          >
            删除
          </button>
        </div>
      </RouterLink>
      <WorkEditView v-if="route.params.workId" :key="String(route.params.workId)" />
      <p v-if="visible.length === 0">没有符合条件的作品。</p>
    </div>
    <ConfirmDialog
      :open="deleteTarget !== null"
      title="删除这组作品"
      :message="`删除「${deleteTarget?.title || '未命名'}」后，列表里不再有它。已经发布的也会从小程序消失。图片仍留在图片库。`"
      confirm-text="确认删除"
      danger
      @cancel="deleteTarget = null"
      @confirm="removeWork"
    />
  </div>
</template>
