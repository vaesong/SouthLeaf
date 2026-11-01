<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import CatalogSaveButtons from "../components/CatalogSaveButtons.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import MediaThumb from "../components/MediaThumb.vue";
import SideDrawer from "../components/SideDrawer.vue";
import MiniHomePreview from "../components/MiniHomePreview.vue";
import PhonePreview from "../components/PhonePreview.vue";
import StatusChip from "../components/StatusChip.vue";
import { mediaContentPath, type WorkView } from "../content/api";
import { FEATURED_LIMIT, FEATURED_WORK_LIMIT, toggleLimitedIds } from "../content/catalogDraft";
import { requireContentApi } from "../content/inject";
import { describeFailure } from "../content/messages";
import { useCatalogAccess, useCatalogLeaveGuard } from "../content/useCatalog";

const api = requireContentApi();
const { store, canEdit } = useCatalogAccess();
const leave = useCatalogLeaveGuard(store);
const leaveOpen = leave.open;
const works = ref<WorkView[]>([]);
const worksError = ref("");
const packageDrawer = ref(false);
const workDrawer = ref(false);
const limitHint = ref("");
const draft = computed(() => store.state.draft);

const carousel = computed(() => draft.value?.featuredWorkIds.slice(0, 3) ?? []);

function workOf(id: string) {
  return works.value.find((item) => item.workId === id);
}

const previewBusinesses = computed(() => {
  const current = draft.value;
  if (!current) return [];
  return current.featuredBusinessIds
    .map((id) => current.businesses.find((item) => item.id === id))
    .filter((item): item is NonNullable<typeof item> => !!item)
    .map((item) => ({
      name: item.name,
      imageUrl: item.coverMediaId ? mediaContentPath(item.coverMediaId, "thumbnail") : "",
    }));
});

const previewWorks = computed(() => {
  const current = draft.value;
  if (!current) return [];
  return current.featuredWorkIds.map((id) => ({
    title: workOf(id)?.title || "",
    imageUrl: workOf(id)?.coverMediaId
      ? mediaContentPath(workOf(id)!.coverMediaId!, "thumbnail")
      : "",
  }));
});

async function refreshWorks() {
  worksError.value = "";
  try {
    works.value = await api.listWorks();
  } catch (error) {
    works.value = [];
    worksError.value = describeFailure(error).text;
  }
}

function pickLimited(list: string[], id: string, max: number, message: string) {
  if (!list.includes(id) && list.length >= max) {
    limitHint.value = message;
    return list;
  }
  limitHint.value = "";
  return toggleLimitedIds(list, id, max);
}

function moveId(list: string[], index: number, offset: number) {
  const next = [...list];
  const target = index + offset;
  if (target < 0 || target >= next.length) return list;
  const [item] = next.splice(index, 1);
  if (item !== undefined) next.splice(target, 0, item);
  return next;
}

function packageOf(id: string) {
  return draft.value?.packages.find((item) => item.id === id);
}

onMounted(async () => {
  await store.load();
  await refreshWorks();
});
</script>

<template>
  <div v-if="draft" class="edit-layout">
    <div class="stack">
      <div class="page-head">
        <div>
          <h1>首页布置</h1>
          <p>客人打开小程序第一眼看到的内容。改完先看右侧预览，再统一保存、发布。</p>
        </div>
        <div class="actions">
          <CatalogSaveButtons :disabled="!canEdit" />
        </div>
      </div>
      <div class="note">
        <b>说明</b>
        <span>轮播图目前取自精选作品的前 3 个。这里不能单独改标题、按钮或跳转。</span>
      </div>
      <div class="card">
        <div class="card-head">
          <h2>首页轮播图</h2>
          <p>只读，来自精选作品</p>
          <div class="right">
            <a class="btn btn-ghost btn-sm" href="#featured-works">调整精选作品</a>
          </div>
        </div>
        <div class="slides">
          <p v-if="carousel.length === 0" class="card-pad">还没有可展示的精选作品。</p>
          <div v-for="id in carousel" :key="id" class="slide">
            <MediaThumb :media-id="workOf(id)?.coverMediaId ?? null" quiet />
            <div class="slide-meta">
              <b>{{ workOf(id)?.title || "未命名作品" }}</b>
              <small>{{ workOf(id) ? "已发布作品" : "作品列表里没有这条" }}</small>
            </div>
          </div>
        </div>
      </div>
      <div class="card">
        <div class="card-head"><h2>首页文案</h2></div>
        <div class="card-pad grid-2">
          <div class="field">
            <label>首页栏目标题</label>
            <input
              v-model="draft.brand.discoveryHeading"
              class="input"
              maxlength="255"
              data-testid="catalog-brand-discovery-heading"
              :disabled="!canEdit"
            />
          </div>
          <div class="field">
            <label>分享卡片标题</label>
            <input
              v-model="draft.brand.shareTitle"
              class="input"
              maxlength="255"
              data-testid="catalog-brand-share-title"
              :disabled="!canEdit"
            />
          </div>
        </div>
      </div>
      <div class="card">
        <div class="card-head">
          <h2>精选业务</h2>
          <p>最多 {{ FEATURED_LIMIT }} 个，按点选顺序排列</p>
        </div>
        <div class="picker">
          <button
            v-for="item in draft.businesses"
            :key="item.id"
            class="pick"
            :class="{ on: draft.featuredBusinessIds.includes(item.id) }"
            type="button"
            :disabled="!canEdit || !item.published"
            data-testid="catalog-featured-business"
            :data-business-id="item.id"
            @click="
              draft.featuredBusinessIds = pickLimited(
                draft.featuredBusinessIds,
                item.id,
                FEATURED_LIMIT,
                '最多选 3 个',
              )
            "
          >
            <span class="no">{{
              draft.featuredBusinessIds.indexOf(item.id) >= 0
                ? draft.featuredBusinessIds.indexOf(item.id) + 1
                : "·"
            }}</span>
            <MediaThumb :media-id="item.coverMediaId" quiet />
            <div class="t">
              {{ item.name || "未命名" }}
              <StatusChip
                :tone="item.published ? 'live' : 'off'"
                :text="item.published ? '已上架' : '未上架'"
              />
            </div>
          </button>
        </div>
        <p v-if="limitHint" class="card-pad hint" data-testid="catalog-featured-limit">
          {{ limitHint }}
        </p>
      </div>
      <div class="card" id="featured-works">
        <div class="card-head">
          <h2>精选作品</h2>
          <div class="right">
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              data-testid="catalog-refresh-works"
              @click="refreshWorks"
            >
              刷新作品
            </button>
          </div>
        </div>
        <div class="card-pad">
          <p v-if="worksError" class="alert-line" role="alert">{{ worksError }}</p>
          <div class="works" style="grid-template-columns: repeat(3, minmax(0, 1fr))">
            <div
              v-for="(id, index) in draft.featuredWorkIds"
              :key="id"
              class="card work"
              data-testid="featured-work-card"
              :data-work-id="id"
            >
              <MediaThumb :media-id="workOf(id)?.coverMediaId ?? null" quiet />
              <div class="work-body">
                <b>{{ workOf(id)?.title || "未命名作品" }}</b>
                <div class="work-foot">
                  <button
                    class="icon-btn"
                    type="button"
                    aria-label="上移"
                    :disabled="!canEdit || index === 0"
                    @click="draft.featuredWorkIds = moveId(draft.featuredWorkIds, index, -1)"
                  >
                    <svg viewBox="0 0 24 24"><path d="M6 14l6-6 6 6" /></svg>
                  </button>
                  <button
                    class="icon-btn"
                    type="button"
                    aria-label="下移"
                    :disabled="!canEdit || index === draft.featuredWorkIds.length - 1"
                    @click="draft.featuredWorkIds = moveId(draft.featuredWorkIds, index, 1)"
                  >
                    <svg viewBox="0 0 24 24"><path d="M6 10l6 6 6-6" /></svg>
                  </button>
                  <button
                    class="icon-btn danger"
                    type="button"
                    aria-label="移除"
                    :disabled="!canEdit"
                    @click="
                      draft.featuredWorkIds = draft.featuredWorkIds.filter((item) => item !== id)
                    "
                  >
                    <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
                  </button>
                </div>
              </div>
            </div>
          </div>
          <button
            class="btn btn-ghost btn-sm"
            type="button"
            data-testid="catalog-featured-works-open"
            :disabled="!canEdit"
            @click="workDrawer = true"
          >
            选择作品
          </button>
        </div>
      </div>
      <div class="card">
        <div class="card-head">
          <h2>精选套餐</h2>
          <p>最多 {{ FEATURED_LIMIT }} 个</p>
          <div class="right">
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              data-testid="catalog-featured-packages-open"
              :disabled="!canEdit"
              @click="packageDrawer = true"
            >
              选择套餐
            </button>
          </div>
        </div>
        <div class="card-pad stack">
          <div
            v-for="(id, index) in draft.featuredPackageIds"
            :key="id"
            class="list-row"
            data-testid="featured-package-row"
            :data-package-id="id"
          >
            <b style="flex: 1">{{ packageOf(id)?.name || "未命名套餐" }}</b>
            <button
              class="icon-btn"
              type="button"
              aria-label="上移"
              :disabled="!canEdit || index === 0"
              @click="draft.featuredPackageIds = moveId(draft.featuredPackageIds, index, -1)"
            >
              <svg viewBox="0 0 24 24"><path d="M6 14l6-6 6 6" /></svg>
            </button>
            <button
              class="icon-btn"
              type="button"
              aria-label="下移"
              :disabled="!canEdit || index === draft.featuredPackageIds.length - 1"
              @click="draft.featuredPackageIds = moveId(draft.featuredPackageIds, index, 1)"
            >
              <svg viewBox="0 0 24 24"><path d="M6 10l6 6 6-6" /></svg>
            </button>
            <button
              class="icon-btn danger"
              type="button"
              aria-label="移除"
              :disabled="!canEdit"
              @click="
                draft.featuredPackageIds = draft.featuredPackageIds.filter((item) => item !== id)
              "
            >
              <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
            </button>
          </div>
          <p v-if="draft.featuredPackageIds.length === 0" class="hint">还没有精选套餐。</p>
        </div>
      </div>
      <SideDrawer :open="workDrawer" title="选择作品" @close="workDrawer = false">
        <div class="picker-grid">
          <button
            v-for="work in works.filter((item) => item.publicationState === 'PUBLISHED')"
            :key="work.workId"
            type="button"
            class="pick"
            :class="{ on: draft.featuredWorkIds.includes(work.workId) }"
            data-testid="work-picker-item"
            :data-work-id="work.workId"
            @click="
              draft.featuredWorkIds = pickLimited(
                draft.featuredWorkIds,
                work.workId,
                FEATURED_WORK_LIMIT,
                '最多选 6 个',
              )
            "
          >
            <MediaThumb :media-id="work.coverMediaId" quiet />
            <div class="t">{{ work.title || "未命名作品" }}</div>
          </button>
        </div>
      </SideDrawer>
      <SideDrawer :open="packageDrawer" title="选择套餐" @close="packageDrawer = false">
        <div v-for="biz in draft.businesses" :key="biz.id" class="stack">
          <h3>{{ biz.name || "未命名业务" }}</h3>
          <button
            v-for="item in draft.packages.filter(
              (pkg) => pkg.published && pkg.businessId === biz.id,
            )"
            :key="item.id"
            type="button"
            class="btn btn-secondary"
            :class="{ 'btn-accent': draft.featuredPackageIds.includes(item.id) }"
            data-testid="package-picker-item"
            :data-package-id="item.id"
            @click="
              draft.featuredPackageIds = pickLimited(
                draft.featuredPackageIds,
                item.id,
                FEATURED_LIMIT,
                '最多选 3 个',
              )
            "
          >
            {{ item.name || "未命名套餐" }}
          </button>
        </div>
      </SideDrawer>
    </div>
    <PhonePreview>
      <template #caption>小程序首页实时预览（草稿）</template>
      <MiniHomePreview
        :title="draft.brand.name"
        :heading="draft.brand.discoveryHeading"
        :address="[draft.contact.address, draft.contact.workingHours].filter(Boolean).join(' · ')"
        :businesses="previewBusinesses"
        :works="previewWorks"
      />
    </PhonePreview>
    <ConfirmDialog
      :open="leaveOpen"
      title="有未保存的修改"
      message="还有没保存的修改。确定离开这一页？"
      confirm-text="离开"
      @cancel="leave.stay"
      @confirm="leave.allow"
    />
  </div>
  <p v-else-if="store.state.status === 'loading'">正在读取草稿…</p>
</template>
