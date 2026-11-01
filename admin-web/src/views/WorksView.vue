<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { authStoreKey } from "../auth/store";
import AppIcon from "../components/AppIcon.vue";
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
const keyword = ref("");
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

const visible = computed(() =>
  works.value.filter((work) => {
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

async function load() {
  loading.value = true;
  notice.value = "";
  try {
    works.value = await api.listWorks();
  } catch (error) {
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
  } finally {
    loading.value = false;
  }
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

onMounted(() => {
  void catalog?.load();
  void load();
});
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>作品集</h1>
        <p>每组作品单独发布。已发布的可以放到首页精选；轮播使用精选作品的前 3 个。</p>
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
    <div class="toolbar">
      <div class="seg">
        <button type="button" :class="{ on: filter === 'all' }" @click="filter = 'all'">
          全部 {{ works.length }}
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
    <p v-if="loading">正在读取作品…</p>
    <div v-else class="works" data-testid="works-table">
      <RouterLink
        v-for="work in visible"
        :key="work.workId"
        class="card work"
        :to="`/works/${work.workId}`"
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
        </div>
      </RouterLink>
      <WorkEditView v-if="route.params.workId" :key="String(route.params.workId)" />
      <p v-if="visible.length === 0">没有符合条件的作品。</p>
    </div>
  </div>
</template>
