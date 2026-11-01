<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import AppSwitch from "../components/AppSwitch.vue";
import CatalogSaveButtons from "../components/CatalogSaveButtons.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import ListEditor from "../components/ListEditor.vue";
import MediaField from "../components/MediaField.vue";
import MediaThumb from "../components/MediaThumb.vue";
import SegmentedControl from "../components/SegmentedControl.vue";
import TagListEditor from "../components/TagListEditor.vue";
import AppIcon from "../components/AppIcon.vue";
import StatusChip from "../components/StatusChip.vue";
import { newIdempotencyKey, type MediaView } from "../content/api";
import { BUSINESS_IDS, PRICE_TYPES, priceNeedsAmount } from "../content/catalogDraft";
import { countLabel, formatPackagePrice } from "../content/format";
import { requireContentApi } from "../content/inject";
import { describeFailure } from "../content/messages";
import { useCatalogAccess, useCatalogLeaveGuard } from "../content/useCatalog";

const api = requireContentApi();
const { store, canEdit } = useCatalogAccess();
const leave = useCatalogLeaveGuard(store);
const leaveOpen = leave.open;

const media = ref<MediaView[]>([]);
const mediaError = ref("");
const uploading = ref(false);
const selectedId = ref<string>(BUSINESS_IDS[0]);
const expandedPackageId = ref<string | null>(null);
let uploadKey: string | null = null;

const draft = computed(() => store.state.draft);
const businesses = computed(() => {
  const value = draft.value;
  if (!value) return [];
  return BUSINESS_IDS.map((id) => value.businesses.find((item) => item.id === id)).filter(
    (item): item is NonNullable<typeof item> => !!item,
  );
});
const selected = computed(
  () =>
    businesses.value.find((item) => item.id === selectedId.value) ?? businesses.value[0] ?? null,
);

function packagesOf(businessId: string) {
  return (
    draft.value?.packages
      .map((item, index) => ({ item, index }))
      .filter((entry) => entry.item.businessId === businessId) ?? []
  );
}

function priceLabel(item: { priceType: string; displayPrice: string }) {
  return formatPackagePrice(item.priceType, item.displayPrice);
}

function moveVisiblePackage(globalIndex: number, offset: number) {
  const value = draft.value;
  if (!value) return;
  const current = value.packages[globalIndex];
  if (!current) return;
  const siblings = value.packages
    .map((item, index) => ({ item, index }))
    .filter((entry) => entry.item.businessId === current.businessId);
  const position = siblings.findIndex((entry) => entry.index === globalIndex);
  const target = siblings[position + offset];
  if (!target) return;
  const swapped = value.packages[globalIndex];
  value.packages[globalIndex] = value.packages[target.index]!;
  value.packages[target.index] = swapped!;
}

async function uploadMedia(file: File) {
  uploading.value = true;
  uploadKey ??= newIdempotencyKey();
  try {
    const result = await api.upload(file, file.name, uploadKey);
    uploadKey = null;
    media.value = [
      result.media,
      ...media.value.filter((item) => item.mediaId !== result.media.mediaId),
    ];
  } catch (error) {
    mediaError.value = describeFailure(error).text;
    if (!describeFailure(error).unknown) uploadKey = null;
  } finally {
    uploading.value = false;
  }
}

function addPackage() {
  const value = draft.value;
  if (!value) return;
  value.packages.push({
    id: `package-${crypto.randomUUID().slice(0, 8)}`,
    businessId: selectedId.value || value.businesses[0]?.id || "child",
    name: "",
    summary: "",
    suitableFor: "",
    durationMinutes: null,
    displayPrice: "",
    priceType: "UNDISCLOSED",
    serviceItems: [],
    tips: [],
    notices: [],
    published: false,
    coverMediaId: null,
    position: value.packages.length,
  });
  expandedPackageId.value = value.packages[value.packages.length - 1]?.id ?? null;
}

function onPriceType(item: { priceType: string; displayPrice: string }, value: string) {
  item.priceType = value;
  if (!priceNeedsAmount(value)) item.displayPrice = "";
}

async function refreshMedia() {
  try {
    media.value = await api.listMedia();
    mediaError.value = "";
  } catch (error) {
    mediaError.value = describeFailure(error).text;
  }
}

onMounted(async () => {
  await store.load();
  await refreshMedia();
});
</script>

<template>
  <div v-if="draft">
    <div class="page-head">
      <div>
        <h1>业务与套餐</h1>
        <p>6 个固定业务，每个业务下可以有多个套餐。价格只作展示，本阶段不收款。</p>
      </div>
      <div class="actions">
        <CatalogSaveButtons :disabled="!canEdit" />
      </div>
    </div>
    <div class="biz-layout">
      <div class="card biz-list">
        <button
          v-for="item in businesses"
          :key="item.id"
          class="biz"
          :class="{ on: item.id === selectedId }"
          type="button"
          data-testid="catalog-business-tab"
          :data-business-id="item.id"
          @click="selectedId = item.id"
        >
          <MediaThumb :media-id="item.coverMediaId" quiet />
          <div>
            <b>{{ item.name || "未命名" }}</b
            ><small>{{ packagesOf(item.id).length }} 个套餐</small>
          </div>
          <StatusChip
            :tone="item.published ? 'live' : 'off'"
            :text="item.published ? '上架' : '下架'"
          />
        </button>
      </div>
      <div class="stack">
        <article v-if="selected" class="card">
          <div class="card-head">
            <h2>{{ selected.name || "未命名" }}</h2>
            <div class="right switch-row">
              在小程序上架
              <AppSwitch
                v-model="selected.published"
                label="上架业务"
                test-id="catalog-business-published"
                :disabled="!canEdit"
              />
            </div>
          </div>
          <div class="biz-cover">
            <MediaField
              v-model="selected.coverMediaId"
              :media="media"
              test-id="catalog-business-cover-media"
              :disabled="!canEdit"
              :uploading="uploading"
              @upload="uploadMedia"
            />
            <div class="stack">
              <div class="field">
                <label
                  >业务名称<em>{{ countLabel(selected.name.length, 255) }}</em></label
                >
                <input
                  v-model="selected.name"
                  class="input"
                  maxlength="255"
                  data-testid="catalog-business-name"
                  :disabled="!canEdit"
                />
              </div>
              <div class="field">
                <label
                  >业务简介<em>{{ countLabel(selected.summary.length, 12000) }}</em></label
                >
                <textarea
                  v-model="selected.summary"
                  class="textarea"
                  maxlength="12000"
                  data-testid="catalog-business-summary"
                  :disabled="!canEdit"
                ></textarea>
              </div>
            </div>
          </div>
        </article>

        <div class="card">
          <div class="card-head">
            <h2>套餐</h2>
            <div class="right">
              <button
                class="btn btn-secondary btn-sm"
                type="button"
                data-testid="catalog-add-package"
                :disabled="!canEdit"
                @click="addPackage"
              >
                <AppIcon name="plus" />新建套餐
              </button>
            </div>
          </div>
          <p
            v-if="packagesOf(selectedId).length === 0"
            class="card-pad"
            data-testid="catalog-packages-empty"
          >
            这个业务还没有套餐。
          </p>
          <article
            v-for="entry in packagesOf(selectedId)"
            :key="entry.item.id"
            data-testid="catalog-package-item"
          >
            <button
              class="pkg-row"
              type="button"
              data-testid="catalog-package-row"
              @click="
                expandedPackageId = expandedPackageId === entry.item.id ? null : entry.item.id
              "
            >
              <MediaThumb :media-id="entry.item.coverMediaId" quiet />
              <div>
                <b data-testid="catalog-package-title">{{ entry.item.name || "未命名套餐" }}</b>
                <small>
                  {{
                    entry.item.durationMinutes
                      ? `约 ${entry.item.durationMinutes} 分钟`
                      : "时长未填"
                  }}
                  · {{ entry.item.suitableFor || "适合人群未填" }}
                </small>
              </div>
              <div class="price">{{ priceLabel(entry.item) }}</div>
              <StatusChip
                :tone="entry.item.published ? 'live' : 'draft'"
                :text="entry.item.published ? '上架' : '草稿'"
              />
            </button>
            <div v-if="expandedPackageId === entry.item.id" class="pkg-edit">
              <div class="grid-2">
                <div class="field">
                  <label>套餐名称</label>
                  <input
                    v-model="entry.item.name"
                    class="input"
                    maxlength="255"
                    data-testid="catalog-package-name"
                    :disabled="!canEdit"
                  />
                </div>
                <div class="field">
                  <label>所属业务</label>
                  <select
                    v-model="entry.item.businessId"
                    class="select"
                    data-testid="catalog-package-business"
                    :disabled="!canEdit"
                  >
                    <option v-for="biz in businesses" :key="biz.id" :value="biz.id">
                      {{ biz.name || "未命名" }}
                    </option>
                  </select>
                </div>
                <div class="field">
                  <label>适合人群</label>
                  <input
                    v-model="entry.item.suitableFor"
                    class="input"
                    maxlength="4000"
                    data-testid="catalog-package-suitable"
                    :disabled="!canEdit"
                  />
                </div>
                <div class="field">
                  <label>拍摄时长（分钟）</label>
                  <input
                    class="input"
                    data-testid="catalog-package-duration"
                    :disabled="!canEdit"
                    :value="entry.item.durationMinutes ?? ''"
                    @input="
                      entry.item.durationMinutes =
                        ($event.target as HTMLInputElement).value.trim() === ''
                          ? null
                          : Number(($event.target as HTMLInputElement).value)
                    "
                  />
                </div>
                <div class="field span-2">
                  <label>套餐简介</label>
                  <textarea
                    v-model="entry.item.summary"
                    class="textarea"
                    maxlength="12000"
                    data-testid="catalog-package-summary"
                    :disabled="!canEdit"
                  ></textarea>
                </div>
                <div class="field">
                  <label>价格显示方式</label>
                  <SegmentedControl
                    :model-value="entry.item.priceType"
                    :options="[...PRICE_TYPES]"
                    label="价格显示方式"
                    data-testid="catalog-package-price-type"
                    :disabled="!canEdit"
                    @update:model-value="onPriceType(entry.item, $event)"
                  />
                  <span class="hint">本阶段不收款，价格仅作展示</span>
                </div>
                <div v-if="priceNeedsAmount(entry.item.priceType)" class="field">
                  <label>显示价格</label>
                  <input
                    v-model="entry.item.displayPrice"
                    class="input"
                    maxlength="255"
                    placeholder="仅填已确认内容"
                    :disabled="!canEdit"
                  />
                </div>
                <div class="field span-2">
                  <label>套餐封面</label>
                  <MediaField
                    v-model="entry.item.coverMediaId"
                    :media="media"
                    test-id="catalog-package-cover-media"
                    :disabled="!canEdit"
                    :uploading="uploading"
                    @upload="uploadMedia"
                  />
                </div>
                <div class="field span-2">
                  <label>服务内容</label>
                  <TagListEditor
                    v-model="entry.item.serviceItems"
                    test-id="catalog-package-service-items"
                    :disabled="!canEdit"
                    :max="30"
                  />
                </div>
                <div class="field">
                  <label>拍摄小贴士</label>
                  <ListEditor
                    v-model="entry.item.tips"
                    test-id="catalog-package-tips"
                    :disabled="!canEdit"
                    :max="30"
                  />
                </div>
                <div class="field">
                  <label>预约须知</label>
                  <ListEditor
                    v-model="entry.item.notices"
                    test-id="catalog-package-notices"
                    :disabled="!canEdit"
                    :max="30"
                  />
                </div>
              </div>
              <div class="switch-row">
                上架这个套餐
                <AppSwitch
                  v-model="entry.item.published"
                  label="上架套餐"
                  test-id="catalog-package-published"
                  :disabled="!canEdit"
                />
                <button
                  class="btn btn-ghost btn-sm"
                  type="button"
                  data-testid="catalog-package-up"
                  :disabled="!canEdit"
                  @click="moveVisiblePackage(entry.index, -1)"
                >
                  上移
                </button>
                <button
                  class="btn btn-ghost btn-sm"
                  type="button"
                  data-testid="catalog-package-down"
                  :disabled="!canEdit"
                  @click="moveVisiblePackage(entry.index, 1)"
                >
                  下移
                </button>
                <button
                  class="btn btn-danger-ghost btn-sm"
                  type="button"
                  data-testid="catalog-package-remove"
                  :disabled="!canEdit"
                  @click="draft.packages.splice(entry.index, 1)"
                >
                  删除套餐
                </button>
              </div>
            </div>
          </article>
        </div>
        <p v-if="mediaError" class="alert-line" role="alert">{{ mediaError }}</p>
      </div>
    </div>
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
