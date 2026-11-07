<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import AppSwitch from "../components/AppSwitch.vue";
import CatalogSaveButtons from "../components/CatalogSaveButtons.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import MediaField from "../components/MediaField.vue";
import MediaThumb from "../components/MediaThumb.vue";
import SegmentedControl from "../components/SegmentedControl.vue";
import SideDrawer from "../components/SideDrawer.vue";
import MiniHomePreview from "../components/MiniHomePreview.vue";
import PhonePreview from "../components/PhonePreview.vue";
import StatusChip from "../components/StatusChip.vue";
import {
  mediaContentPath,
  type HeroSlideInput,
  type HeroTargetType,
  type MediaView,
  type WorkView,
} from "../content/api";
import {
  FEATURED_LIMIT,
  FEATURED_WORK_LIMIT,
  HERO_LIMIT,
  presentationGroups,
  moveItem,
  syncMediaAltTexts,
  toggleLimitedIds,
} from "../content/catalogDraft";
import { countLabel } from "../content/format";
import { requireContentApi } from "../content/inject";
import { describeFailure } from "../content/messages";
import {
  rememberUploadedMedia,
  uploadFilesSequentially,
  uploadFailureText,
} from "../content/uploadFiles";
import { useCatalogAccess, useCatalogLeaveGuard } from "../content/useCatalog";

const copyGroups = presentationGroups();
const api = requireContentApi();
const { store, canEdit } = useCatalogAccess();
const leave = useCatalogLeaveGuard(store);
const leaveOpen = leave.open;
const works = ref<WorkView[]>([]);
const worksError = ref("");
const media = ref<MediaView[]>([]);
const mediaError = ref("");
const uploading = ref(false);
const packageDrawer = ref(false);
const workDrawer = ref(false);
const limitHint = ref("");
const editingSlideId = ref<string | null>(null);
const draft = computed(() => store.state.draft);
const heroTargets = [
  { value: "WORK", label: "作品" },
  { value: "PACKAGE", label: "套餐" },
  { value: "BUSINESS", label: "业务" },
  { value: "ABOUT", label: "关于我们" },
] as const;

const heroSlides = computed(() =>
  [...(draft.value?.heroSlides ?? [])].sort(
    (a, b) => a.position - b.position || a.id.localeCompare(b.id),
  ),
);

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

const previewHero = computed(() => {
  const slide = heroSlides.value.find((item) => item.enabled);
  if (!slide) return null;
  return {
    title: slide.title,
    subtitle: slide.subtitle,
    action: slide.actionText,
    imageUrl: slide.mediaId ? mediaContentPath(slide.mediaId, "thumbnail") : "",
  };
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

async function refreshMedia() {
  mediaError.value = "";
  try {
    media.value = await api.listMedia();
  } catch (error) {
    media.value = [];
    mediaError.value = describeFailure(error).text;
  }
}

async function uploadMedia(files: File[], folderId: string | null = null) {
  uploading.value = true;
  mediaError.value = "";
  const failure = await uploadFilesSequentially(files, async (file, key) => {
    const result = await api.upload(file, file.name, key, folderId);
    media.value = rememberUploadedMedia(media.value, result.media);
  });
  if (failure) mediaError.value = uploadFailureText(failure.file.name, failure.error);
  uploading.value = false;
}

function heroTargetLabel(type: string) {
  return heroTargets.find((item) => item.value === type)?.label ?? "其他";
}

function setHeroTarget(slide: HeroSlideInput, type: string) {
  const next = heroTargets.some((item) => item.value === type) ? (type as HeroTargetType) : "WORK";
  slide.target = next === "ABOUT" ? { type: "ABOUT", id: "about" } : { type: next, id: "" };
}

function setSlideMedia(slide: HeroSlideInput, mediaId: string | null) {
  slide.mediaId = mediaId ?? "";
  if (draft.value) syncMediaAltTexts(draft.value);
}

function addSlide() {
  const value = draft.value;
  if (!value || value.heroSlides.length >= HERO_LIMIT) return;
  const id = `hero-${crypto.randomUUID()}`;
  value.heroSlides.push({
    id,
    mediaId: "",
    title: "",
    subtitle: "",
    actionText: "查看详情",
    target: { type: "WORK", id: "" },
    enabled: true,
    position: value.heroSlides.length,
  });
  editingSlideId.value = id;
}

function orderedSlides() {
  return [...(draft.value?.heroSlides ?? [])].sort(
    (a, b) => a.position - b.position || a.id.localeCompare(b.id),
  );
}

function moveSlide(index: number, offset: number) {
  const value = draft.value;
  if (!value) return;
  const ordered = orderedSlides();
  moveItem(ordered, index, offset);
  ordered.forEach((slide, position) => {
    slide.position = position;
  });
  value.heroSlides = ordered;
}

function removeSlide(id: string) {
  const value = draft.value;
  if (!value) return;
  const ordered = orderedSlides().filter((slide) => slide.id !== id);
  ordered.forEach((slide, position) => {
    slide.position = position;
  });
  value.heroSlides = ordered;
  if (editingSlideId.value === id) editingSlideId.value = null;
  syncMediaAltTexts(value);
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
  await Promise.all([refreshWorks(), refreshMedia()]);
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
      <div class="card">
        <div class="card-head">
          <h2>首页轮播图</h2>
          <p>最多 {{ HERO_LIMIT }} 张。没有启用的轮播时，自动显示精选作品前 3 个。</p>
          <div class="right">
            <button
              class="btn btn-secondary btn-sm"
              type="button"
              data-testid="catalog-hero-add"
              :disabled="!canEdit || heroSlides.length >= HERO_LIMIT"
              @click="addSlide"
            >
              添加一张
            </button>
          </div>
        </div>
        <div class="slides">
          <p v-if="mediaError" class="alert-line" role="alert">{{ mediaError }}</p>
          <p v-if="heroSlides.length === 0" class="card-pad">
            还没有轮播。可以添加，也可以先留空。
          </p>
          <div
            v-for="(slide, index) in heroSlides"
            :key="slide.id"
            class="slide"
            :class="{ editing: editingSlideId === slide.id }"
            data-testid="catalog-hero-slide"
          >
            <span class="grip" aria-hidden="true">⋮⋮</span>
            <MediaThumb :media-id="slide.mediaId || null" quiet />
            <div class="slide-meta">
              <b>{{ slide.title || "未填写标题" }}</b>
              <small
                >副标题：{{ slide.subtitle || "无" }} · 按钮：{{
                  slide.actionText || "未填写"
                }}</small
              >
              <div class="tags">
                <StatusChip
                  :tone="slide.enabled ? 'live' : 'off'"
                  :text="slide.enabled ? '显示中' : '已隐藏'"
                />
                <span class="chip chip-accent">跳转：{{ heroTargetLabel(slide.target.type) }}</span>
              </div>
            </div>
            <div class="slide-tools">
              <AppSwitch v-model="slide.enabled" label="显示" :disabled="!canEdit" />
              <button
                class="icon-btn"
                type="button"
                aria-label="上移"
                :disabled="!canEdit || index === 0"
                @click="moveSlide(index, -1)"
              >
                <svg viewBox="0 0 24 24"><path d="M6 14l6-6 6 6" /></svg>
              </button>
              <button
                class="icon-btn"
                type="button"
                aria-label="下移"
                :disabled="!canEdit || index === heroSlides.length - 1"
                @click="moveSlide(index, 1)"
              >
                <svg viewBox="0 0 24 24"><path d="M6 10l6 6 6-6" /></svg>
              </button>
              <button
                class="icon-btn"
                type="button"
                :aria-label="editingSlideId === slide.id ? '收起' : '编辑'"
                :disabled="!canEdit"
                @click="editingSlideId = editingSlideId === slide.id ? null : slide.id"
              >
                <svg viewBox="0 0 24 24"><path d="M4 20h4L19 9l-4-4L4 16z" /></svg>
              </button>
              <button
                class="icon-btn danger"
                type="button"
                aria-label="删除"
                :disabled="!canEdit"
                @click="removeSlide(slide.id)"
              >
                <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
              </button>
            </div>
            <div v-if="editingSlideId === slide.id" class="slide-form">
              <div class="field span-2">
                <label>图片</label>
                <MediaField
                  :model-value="slide.mediaId || null"
                  :media="media"
                  :disabled="!canEdit"
                  :uploading="uploading"
                  :notice="mediaError"
                  test-id="catalog-hero-media"
                  @update:model-value="setSlideMedia(slide, $event)"
                  @upload="uploadMedia"
                />
              </div>
              <div class="field">
                <label
                  >主标题<em>{{ countLabel(slide.title.length, 16) }}</em></label
                >
                <input
                  v-model="slide.title"
                  class="input"
                  maxlength="16"
                  data-testid="catalog-hero-title"
                  :disabled="!canEdit"
                />
              </div>
              <div class="field">
                <label>副标题<em>选填</em></label>
                <input
                  v-model="slide.subtitle"
                  class="input"
                  maxlength="64"
                  data-testid="catalog-hero-subtitle"
                  :disabled="!canEdit"
                />
              </div>
              <div class="field">
                <label>按钮文字</label>
                <input
                  v-model="slide.actionText"
                  class="input"
                  maxlength="8"
                  data-testid="catalog-hero-action"
                  :disabled="!canEdit"
                />
              </div>
              <div class="field">
                <label>点击后去哪里</label>
                <SegmentedControl
                  :model-value="slide.target.type"
                  :options="[...heroTargets]"
                  label="点击后去哪里"
                  :disabled="!canEdit"
                  @update:model-value="setHeroTarget(slide, $event)"
                />
              </div>
              <div v-if="slide.target.type === 'WORK'" class="field span-2">
                <label>选择作品</label>
                <select
                  v-model="slide.target.id"
                  class="select input"
                  data-testid="catalog-hero-target"
                  :disabled="!canEdit"
                >
                  <option value="">请选择</option>
                  <option
                    v-if="
                      slide.target.id &&
                      !works.some(
                        (item) =>
                          item.workId === slide.target.id && item.publicationState === 'PUBLISHED',
                      )
                    "
                    :value="slide.target.id"
                  >
                    这条内容现在不可用
                  </option>
                  <option
                    v-for="work in works.filter((item) => item.publicationState === 'PUBLISHED')"
                    :key="work.workId"
                    :value="work.workId"
                  >
                    {{ work.title || "未命名作品" }}
                  </option>
                </select>
              </div>
              <div v-else-if="slide.target.type === 'PACKAGE'" class="field span-2">
                <label>选择套餐</label>
                <select
                  v-model="slide.target.id"
                  class="select input"
                  data-testid="catalog-hero-target"
                  :disabled="!canEdit"
                >
                  <option value="">请选择</option>
                  <option
                    v-if="
                      slide.target.id &&
                      !draft.packages.some((item) => item.published && item.id === slide.target.id)
                    "
                    :value="slide.target.id"
                  >
                    这条内容现在不可用
                  </option>
                  <option
                    v-for="item in draft.packages.filter((pkg) => pkg.published)"
                    :key="item.id"
                    :value="item.id"
                  >
                    {{ item.name || "未命名套餐" }}
                  </option>
                </select>
              </div>
              <div v-else-if="slide.target.type === 'BUSINESS'" class="field span-2">
                <label>选择业务</label>
                <select
                  v-model="slide.target.id"
                  class="select input"
                  data-testid="catalog-hero-target"
                  :disabled="!canEdit"
                >
                  <option value="">请选择</option>
                  <option
                    v-if="
                      slide.target.id &&
                      !draft.businesses.some(
                        (item) => item.published && item.id === slide.target.id,
                      )
                    "
                    :value="slide.target.id"
                  >
                    这条内容现在不可用
                  </option>
                  <option
                    v-for="item in draft.businesses.filter((biz) => biz.published)"
                    :key="item.id"
                    :value="item.id"
                  >
                    {{ item.name || "未命名业务" }}
                  </option>
                </select>
              </div>
              <div v-else class="field span-2">
                <p class="hint">打开后进入关于我们。</p>
              </div>
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
      <div v-if="draft.presentation" class="card">
        <div class="card-head">
          <h2>小程序文案</h2>
          <p>留空则顾客看不到这句。保存并发布之后才会出现。</p>
        </div>
        <div v-for="group in copyGroups" :key="group.name" class="card-pad">
          <p class="hint">{{ group.name }}</p>
          <div class="grid-2">
            <div v-for="field in group.fields" :key="field.key" class="field">
              <label>{{ field.label }}</label>
              <input
                v-model="draft.presentation[field.key]"
                class="input"
                maxlength="400"
                :disabled="!canEdit"
                :data-testid="'catalog-presentation-' + field.key"
              />
            </div>
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
        :hero="previewHero"
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
