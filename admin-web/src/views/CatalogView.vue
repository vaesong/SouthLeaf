<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from "vue";
import { authStoreKey } from "../auth/store";
import { AdminApiError } from "../auth/api";
import {
  newIdempotencyKey,
  mediaContentPath,
  type MediaView,
  type CatalogDraftInput,
  type CatalogPreview,
  type CatalogVersionView,
  type CatalogView as StoredCatalog,
  type WorkView,
} from "../content/api";
import { requireContentApi } from "../content/inject";
import { describeFailure } from "../content/messages";

const api = requireContentApi();
const auth = inject(authStoreKey);
const stored = ref<StoredCatalog | null>(null);
const draft = ref<CatalogDraftInput | null>(null);
const preview = ref<CatalogPreview | null>(null);
const versions = ref<CatalogVersionView[]>([]);
const mediaAssets = ref<MediaView[]>([]);
const mediaLoadError = ref("");
const works = ref<WorkView[]>([]);
const worksLoadError = ref("");
const selectedVersion = ref<number | null>(null);
const loading = ref(true);
const busy = ref("");
const notice = ref("");
const requestId = ref("");
const unknownAction = ref<"" | "publish" | "withdraw">("");
let publishKey: string | null = null;
let withdrawKey: string | null = null;
const canEdit = computed(() => auth?.state.identity?.permissions.includes("CONTENT_EDIT") ?? false);
const canPublish = computed(
  () => auth?.state.identity?.permissions.includes("CONTENT_PUBLISH") ?? false,
);
const referencedMediaIds = computed(() => {
  const value = draft.value;
  if (!value) return [];
  return [
    ...new Set(
      [
        value.brand.logoMediaId,
        ...value.team.map((x) => x.portraitMediaId),
        ...value.channels.map((x) => x.qrMediaId),
        ...value.businesses.map((x) => x.coverMediaId),
        ...value.packages.map((x) => x.coverMediaId),
      ].filter((id): id is string => typeof id === "string" && id.length > 0),
    ),
  ];
});
watch(referencedMediaIds, (ids) => {
  if (!draft.value) return;
  const before = draft.value.mediaAltTexts ?? {};
  draft.value.mediaAltTexts = Object.fromEntries(ids.map((id) => [id, before[id] ?? ""]));
});

function fail(error: unknown): boolean {
  const result = describeFailure(error);
  notice.value = result.text;
  requestId.value = result.requestId;
  return result.unknown;
}
function accept(value: StoredCatalog) {
  stored.value = value;
  draft.value = structuredClone(value.draft);
  preview.value = null;
}
async function refreshMedia() {
  mediaLoadError.value = "";
  try {
    mediaAssets.value = await api.listMedia();
  } catch (error) {
    mediaAssets.value = [];
    mediaLoadError.value = describeFailure(error).text;
  }
}
async function refreshWorks() {
  worksLoadError.value = "";
  try {
    works.value = await api.listWorks();
  } catch (error) {
    works.value = [];
    worksLoadError.value = describeFailure(error).text;
  }
}
function mediaThumbnail(mediaId: string | null) {
  return mediaId ? mediaContentPath(mediaId, "thumbnail") : "";
}
async function refreshVersions() {
  versions.value = await api.listCatalogVersions();
  selectedVersion.value = versions.value[0]?.revision ?? null;
}
async function loadVersion() {
  if (selectedVersion.value === null || !draft.value) return;
  if (
    !window.confirm(
      "加载历史版本将替换编辑器中尚未保存的内容；服务器当前修订不变，保存后会创建一个新修订。继续？",
    )
  )
    return;
  busy.value = "history";
  try {
    const historic = await api.getCatalogVersion(selectedVersion.value);
    draft.value = structuredClone(historic.draft);
    preview.value = null;
    notice.value = `已载入历史修订 ${historic.revision}；当前服务器修订仍为 ${stored.value?.revision}，保存将生成新版本。`;
  } catch (error) {
    fail(error);
  } finally {
    busy.value = "";
  }
}
async function load() {
  loading.value = true;
  notice.value = "";
  try {
    accept(await api.getCatalog());
    await Promise.all([refreshVersions(), refreshMedia(), refreshWorks()]);
  } catch (error) {
    fail(error);
  } finally {
    loading.value = false;
  }
}
function normalizeDraft() {
  if (!draft.value) return;
  const value = draft.value;
  value.brand.logoMediaId = emptyToNull(value.brand.logoMediaId);
  value.team.forEach((x, i) => {
    x.position = i;
    x.portraitMediaId = emptyToNull(x.portraitMediaId);
  });
  value.channels.forEach((x, i) => {
    x.position = i;
    x.qrMediaId = emptyToNull(x.qrMediaId);
  });
  value.packages.forEach((x, i) => {
    x.position = i;
    x.coverMediaId = emptyToNull(x.coverMediaId);
    if ((x.durationMinutes as unknown) === "") x.durationMinutes = null;
  });
  value.businesses.forEach((x) => {
    x.coverMediaId = emptyToNull(x.coverMediaId);
  });
  // Refresh exact alt-text keys after normalizing nullable IDs.
  const ids = new Set(
    [
      value.brand.logoMediaId,
      ...value.team.map((x) => x.portraitMediaId),
      ...value.channels.map((x) => x.qrMediaId),
      ...value.businesses.map((x) => x.coverMediaId),
      ...value.packages.map((x) => x.coverMediaId),
    ].filter((id): id is string => !!id),
  );
  value.mediaAltTexts = Object.fromEntries(
    [...ids].map((id) => [id, value.mediaAltTexts[id] ?? ""]),
  );
}
function emptyToNull(value: string | null): string | null {
  return value === null || value.trim() === "" ? null : value;
}
function normalizePrice(item: CatalogDraftInput["packages"][number]) {
  if (item.priceType === "INQUIRY" || item.priceType === "UNDISCLOSED") item.displayPrice = "";
}
async function save() {
  if (!draft.value || !stored.value) return;
  normalizeDraft();
  busy.value = "save";
  notice.value = "";
  requestId.value = "";
  try {
    accept(await api.saveCatalog(stored.value.revision, draft.value));
    await refreshVersions();
    notice.value = "草稿已保存为新修订；公开页面未改变。";
  } catch (error) {
    fail(error);
  } finally {
    busy.value = "";
  }
}
async function checkPreview() {
  busy.value = "preview";
  notice.value = "";
  try {
    preview.value = await api.previewCatalog();
    notice.value = preview.value.publishable
      ? "校验通过，可以发布。"
      : `暂不能发布：${preview.value.blockingReason ?? "字段或引用不完整"}`;
  } catch (error) {
    fail(error);
  } finally {
    busy.value = "";
  }
}
async function publish() {
  if (!stored.value) return;
  busy.value = "publish";
  notice.value = "";
  publishKey ??= newIdempotencyKey();
  try {
    const result = await api.publishCatalog(stored.value.revision, publishKey);
    publishKey = null;
    notice.value = `已发布目录修订 ${result.revision}（${result.contentVersion}）。`;
  } catch (error) {
    unknownAction.value = fail(error) ? "publish" : "";
  } finally {
    busy.value = "";
  }
}
async function withdraw() {
  if (!stored.value) return;
  busy.value = "withdraw";
  notice.value = "";
  withdrawKey ??= newIdempotencyKey();
  try {
    await api.withdrawCatalog(stored.value.revision, withdrawKey);
    withdrawKey = null;
    notice.value = "当前目录已下架；旧版不会自动恢复为当前内容。";
  } catch (error) {
    unknownAction.value = fail(error) ? "withdraw" : "";
  } finally {
    busy.value = "";
  }
}
async function resolveUnknownCommand() {
  const key = unknownAction.value === "publish" ? publishKey : withdrawKey;
  if (!key) return;
  busy.value = "resolve";
  try {
    const result = await api.findCatalogCommand(key);
    if (unknownAction.value === "publish") publishKey = null;
    else withdrawKey = null;
    unknownAction.value = "";
    notice.value = `服务器已确认命令 ${result.commandId} 完成（修订 ${result.revision}）。`;
  } catch (error) {
    if (error instanceof AdminApiError && error.status === 404) {
      notice.value = "尚未找到已完成命令；如需重试，请继续使用同一操作键。";
      requestId.value = error.requestId ?? "";
    } else fail(error);
  } finally {
    busy.value = "";
  }
}
function moveDraftItem<T>(items: T[], index: number, offset: number) {
  const target = index + offset;
  if (target < 0 || target >= items.length) return;
  const [item] = items.splice(index, 1);
  if (item !== undefined) items.splice(target, 0, item);
}
function setFeaturedWorks(event: Event) {
  if (!draft.value) return;
  const select = event.target as HTMLSelectElement;
  draft.value.featuredWorkIds = Array.from(select.selectedOptions, (option) => option.value);
}
function addMember() {
  if (!draft.value) return;
  draft.value.team.push({
    id: `member-${crypto.randomUUID().slice(0, 8)}`,
    name: "",
    role: "",
    bio: "",
    specialties: [],
    portraitMediaId: null,
    position: draft.value.team.length,
    published: false,
  });
}
function addChannel() {
  if (!draft.value) return;
  draft.value.channels.push({
    id: `channel-${crypto.randomUUID().slice(0, 8)}`,
    platform: "wechat",
    label: "",
    account: "",
    qrMediaId: null,
    qrStatus: "UNCONFIGURED",
    position: draft.value.channels.length,
    published: false,
  });
}
function addPackage() {
  if (!draft.value) return;
  draft.value.packages.push({
    id: `package-${crypto.randomUUID().slice(0, 8)}`,
    businessId: draft.value.businesses[0]?.id ?? "child",
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
    position: draft.value.packages.length,
  });
}
function lines(value: string): string[] {
  return value
    .split("\n")
    .map((item) => item.trim())
    .filter(Boolean);
}
function listText(value: string[]): string {
  return value.join("\n");
}
function updateLines(
  target: CatalogDraftInput["packages"][number],
  key: "serviceItems" | "tips" | "notices",
  value: string,
) {
  target[key] = lines(value);
}
onMounted(load);
</script>

<template>
  <main class="identity-shell content-shell" data-testid="catalog-screen">
    <header class="topbar">
      <router-link class="wordmark" to="/" aria-label="管理首頁"
        ><span class="wordmark-mark">S</span
        ><span>SouthLeaf <small>ADMIN</small></span></router-link
      >
      <div class="topbar-status">
        <span class="environment-badge">M3 · 合成资料工作区（非生产）</span>
      </div>
    </header>
    <section class="identity-card content-card">
      <div class="section-title-row">
        <div>
          <p class="eyebrow">CATALOG AUTHORING</p>
          <h1>品牌与目录</h1>
        </div>
        <span v-if="stored" data-testid="catalog-stored-revision">修订 {{ stored.revision }}</span>
      </div>
      <p class="panel-copy">
        草稿不会改变公开内容。只填写已确认资料；价格类型必须明确，未确认价格请保持“未披露”。
      </p>
      <fieldset v-if="versions.length" class="catalog-section history-section">
        <legend>草稿版本历史</legend>
        <div class="catalog-grid">
          <label
            >选择已保存修订<select v-model.number="selectedVersion" data-testid="catalog-history-revision">
              <option v-for="version in versions" :key="version.revision" :value="version.revision">
                修订 {{ version.revision }} · {{ version.updatedAt }} · {{ version.updatedBy }}
              </option>
            </select></label
          >
          <div class="history-action">
            <el-button
              :disabled="busy !== '' || selectedVersion === stored?.revision"
              :loading="busy === 'history'"
              @click="loadVersion"
              data-testid="catalog-load-history"
              >载入到编辑器</el-button
            ><el-button :disabled="busy !== ''" @click="refreshVersions">刷新历史</el-button>
          </div>
        </div>
        <p class="panel-copy">
          历史快照只读保留；载入后需以当前修订号保存，系统会追加新修订，不覆盖历史。
        </p>
      </fieldset>
      <p v-if="loading" role="status">正在读取草稿…</p>
      <template v-else-if="draft">
        <fieldset class="catalog-section">
          <legend>品牌</legend>
          <div class="catalog-grid">
            <label>品牌名称<input v-model="draft.brand.name" data-testid="catalog-brand-name" maxlength="255" /></label>
            <label>英文名称<input v-model="draft.brand.englishName" data-testid="catalog-brand-english-name" maxlength="255" /></label>
            <label
              >发现页标题<input v-model="draft.brand.discoveryHeading" data-testid="catalog-brand-discovery-heading" maxlength="255"
            /></label>
            <label>分享标题<input v-model="draft.brand.shareTitle" data-testid="catalog-brand-share-title" maxlength="255" /></label>
            <label>品牌标语<input v-model="draft.brand.tagline" data-testid="catalog-brand-tagline" maxlength="1024" /></label>
            <label
              >Logo 媒体<select v-model="draft.brand.logoMediaId" data-testid="catalog-brand-logo-media">
                <option :value="null">不使用媒体</option>
                <option v-for="media in mediaAssets" :key="media.mediaId" :value="media.mediaId" :disabled="media.status !== 'READY'">{{ media.mediaId }} · {{ media.status }} · {{ media.sourceWidth }}×{{ media.sourceHeight }}</option>
              </select></label>
            <label class="wide"
              >品牌介绍<textarea
                v-model="draft.brand.description"
                data-testid="catalog-brand-description"
                rows="4"
                maxlength="16000"
              ></textarea>
            </label>
          </div>
        </fieldset>
        <fieldset class="catalog-section">
          <legend>联系资料</legend>
          <div class="catalog-grid">
            <label>电话<input v-model="draft.contact.phone" data-testid="catalog-contact-phone" maxlength="64" /></label
            ><label>地址<input v-model="draft.contact.address" data-testid="catalog-contact-address" maxlength="1024" /></label
            ><label>营业时间<input v-model="draft.contact.workingHours" data-testid="catalog-contact-hours" maxlength="1024" /></label>
          </div>
        </fieldset>
        <fieldset class="catalog-section">
          <legend>
            团队成员
            <button class="minor" type="button" :disabled="!canEdit" @click="addMember" data-testid="catalog-add-team">
              ＋ 添加成员
            </button>
          </legend>
          <article v-for="(member, index) in draft.team" :key="member.id" class="catalog-item" data-testid="catalog-team-item">
            <div class="catalog-grid">
              <label>姓名<input v-model="member.name" data-testid="catalog-team-name" /></label
              ><label>角色<input v-model="member.role" data-testid="catalog-team-role" /></label
              ><label>成员 ID<input v-model="member.id" /></label
              ><label>头像媒体<select v-model="member.portraitMediaId" data-testid="catalog-team-portrait-media"><option :value="null">不使用媒体</option><option v-for="media in mediaAssets" :key="media.mediaId" :value="media.mediaId" :disabled="media.status !== 'READY'">{{ media.mediaId }} · {{ media.status }}</option></select></label
              ><label class="wide">简介<textarea v-model="member.bio" data-testid="catalog-team-bio" rows="2"></textarea></label
              ><label class="wide"
                >专长（每行一项）<textarea
                  :value="listText(member.specialties)"
                  data-testid="catalog-team-specialties"
                  rows="2"
                  @input="member.specialties = lines(($event.target as HTMLTextAreaElement).value)"
                ></textarea></label
              ><label class="check"
                ><input v-model="member.published" data-testid="catalog-team-published" type="checkbox" />公开展示</label
              ><button class="minor" type="button" :disabled="!canEdit || index === 0" @click="moveDraftItem(draft!.team, index, -1)" data-testid="catalog-team-up">上移</button>
              <button class="minor" type="button" :disabled="!canEdit || index === draft!.team.length - 1" @click="moveDraftItem(draft!.team, index, 1)" data-testid="catalog-team-down">下移</button>
              <button
                class="minor danger"
                type="button"
                @click="draft!.team = draft!.team.filter((x) => x.id !== member.id)"
                data-testid="catalog-team-remove"
              >
                移除成员
              </button>
            </div>
          </article>
          <p v-if="!draft.team.length" class="empty-permissions">暂无成员。</p>
        </fieldset>
        <fieldset class="catalog-section">
          <legend>
            联系渠道
            <button class="minor" type="button" :disabled="!canEdit" @click="addChannel" data-testid="catalog-add-channel">
              ＋ 添加渠道
            </button>
          </legend>
          <article v-for="(channel, index) in draft.channels" :key="channel.id" class="catalog-item" data-testid="catalog-channel-item">
            <div class="catalog-grid">
              <label
                >平台<select v-model="channel.platform" data-testid="catalog-channel-platform">
                  <option value="wechat">微信</option>
                  <option value="douyin">抖音</option>
                  <option value="xiaohongshu">小红书</option>
                </select></label
              ><label>展示名称<input v-model="channel.label" data-testid="catalog-channel-label" /></label
              ><label>账号<input v-model="channel.account" data-testid="catalog-channel-account" /></label
              ><label>二维码媒体<select v-model="channel.qrMediaId" data-testid="catalog-channel-qr-media"><option :value="null">不使用媒体</option><option v-for="media in mediaAssets" :key="media.mediaId" :value="media.mediaId" :disabled="media.status !== 'READY'">{{ media.mediaId }} · {{ media.status }}</option></select></label
              ><label
                >二维码状态<select v-model="channel.qrStatus" data-testid="catalog-channel-qr-status">
                  <option>UNCONFIGURED</option>
                  <option>PLACEHOLDER</option>
                  <option>CONFIRMED</option>
                </select></label
              ><label class="check"
                ><input v-model="channel.published" data-testid="catalog-channel-published" type="checkbox" />公开展示</label
              ><button class="minor" type="button" :disabled="!canEdit || index === 0" @click="moveDraftItem(draft!.channels, index, -1)" data-testid="catalog-channel-up">上移</button>
              <button class="minor" type="button" :disabled="!canEdit || index === draft!.channels.length - 1" @click="moveDraftItem(draft!.channels, index, 1)" data-testid="catalog-channel-down">下移</button>
              <button
                class="minor danger"
                type="button"
                @click="draft!.channels = draft!.channels.filter((x) => x.id !== channel.id)"
              >
                移除渠道
              </button>
            </div>
          </article>
          <p v-if="!draft.channels.length" class="empty-permissions">暂无渠道。</p>
        </fieldset>
        <fieldset class="catalog-section">
          <legend>六类业务</legend>
          <article v-for="business in draft.businesses" :key="business.id" class="catalog-item" data-testid="catalog-business-item">
            <div class="catalog-grid">
              <label>业务 ID<input :value="business.id" data-testid="catalog-business-id" disabled /></label
              ><label>展示名称<input v-model="business.name" data-testid="catalog-business-name" /></label
              ><label>封面媒体<select v-model="business.coverMediaId" data-testid="catalog-business-cover-media"><option :value="null">不使用媒体</option><option v-for="media in mediaAssets" :key="media.mediaId" :value="media.mediaId" :disabled="media.status !== 'READY'">{{ media.mediaId }} · {{ media.status }}</option></select></label
              ><label class="wide"
                >业务介绍<textarea v-model="business.summary" data-testid="catalog-business-summary" rows="2"></textarea></label
              ><label class="check"
                ><input v-model="business.published" data-testid="catalog-business-published" type="checkbox" />公开展示</label
              >
            </div>
          </article>
        </fieldset>
        <fieldset class="catalog-section">
          <legend>
            套餐与服务项目
            <button class="minor" type="button" :disabled="!canEdit" @click="addPackage" data-testid="catalog-add-package">
              ＋ 添加套餐
            </button>
          </legend>
          <article v-for="(item, index) in draft.packages" :key="item.id" class="catalog-item" data-testid="catalog-package-item">
            <div class="catalog-grid">
              <label>套餐 ID<input v-model="item.id" data-testid="catalog-package-id" /></label
              ><label
                >所属业务<select v-model="item.businessId" data-testid="catalog-package-business">
                  <option
                    v-for="business in draft.businesses"
                    :key="business.id"
                    :value="business.id"
                  >
                    {{ business.name || business.id }}
                  </option>
                </select></label
              ><label>名称<input v-model="item.name" data-testid="catalog-package-name" /></label
              ><label>适用对象<input v-model="item.suitableFor" data-testid="catalog-package-suitable" /></label
              ><label
                >时长（分钟）<input
                  v-model.number="item.durationMinutes"
                  data-testid="catalog-package-duration"
                  type="number"
                  min="1" /></label
              ><label
                >价格类型<select v-model="item.priceType" data-testid="catalog-package-price-type" @change="normalizePrice(item)">
                  <option value="FIXED">固定价</option>
                  <option value="FROM">起价</option>
                  <option value="INQUIRY">需咨询</option>
                  <option value="UNDISCLOSED">未披露</option>
                </select></label
              ><label v-if="item.priceType === 'FIXED' || item.priceType === 'FROM'"
                >价格展示文案<input
                  v-model="item.displayPrice"
                  data-testid="catalog-package-display-price"
                  placeholder="仅填已确认内容" /></label
              ><label class="wide"
                >套餐简介<textarea v-model="item.summary" data-testid="catalog-package-summary" rows="2"></textarea></label
              ><label class="wide"
                >服务项目（每行一项）<textarea
                  :value="listText(item.serviceItems)"
                  data-testid="catalog-package-service-items"
                  rows="3"
                  @input="
                    updateLines(item, 'serviceItems', ($event.target as HTMLTextAreaElement).value)
                  "
                ></textarea></label
              ><label class="wide"
                >提示（每行一项）<textarea
                  :value="listText(item.tips)"
                  data-testid="catalog-package-tips"
                  rows="2"
                  @input="updateLines(item, 'tips', ($event.target as HTMLTextAreaElement).value)"
                ></textarea></label
              ><label class="wide"
                >注意事项（每行一项）<textarea
                  :value="listText(item.notices)"
                  data-testid="catalog-package-notices"
                  rows="2"
                  @input="
                    updateLines(item, 'notices', ($event.target as HTMLTextAreaElement).value)
                  "
                ></textarea></label
              ><label>封面媒体<select v-model="item.coverMediaId" data-testid="catalog-package-cover-media"><option :value="null">不使用媒体</option><option v-for="media in mediaAssets" :key="media.mediaId" :value="media.mediaId" :disabled="media.status !== 'READY'">{{ media.mediaId }} · {{ media.status }}</option></select></label
              ><label class="check"
                ><input v-model="item.published" data-testid="catalog-package-published" type="checkbox" />公开展示</label
              ><button class="minor" type="button" :disabled="!canEdit || index === 0" @click="moveDraftItem(draft!.packages, index, -1)" data-testid="catalog-package-up">上移</button>
              <button class="minor" type="button" :disabled="!canEdit || index === draft!.packages.length - 1" @click="moveDraftItem(draft!.packages, index, 1)" data-testid="catalog-package-down">下移</button>
              <button
                class="minor danger"
                type="button"
                @click="draft!.packages = draft!.packages.filter((x) => x.id !== item.id)"
                data-testid="catalog-package-remove"
              >
                移除套餐
              </button>
            </div>
          </article>
          <p v-if="!draft.packages.length" class="empty-permissions" data-testid="catalog-packages-empty">暂无套餐（合法空态）。</p>
        </fieldset>
        <fieldset class="catalog-section">
          <legend>媒体替代文字</legend>
          <div class="media-toolbar"><span>仅可选择 READY 媒体（{{ mediaAssets.filter((item) => item.status === "READY").length }} 项）</span><el-button data-testid="catalog-refresh-media" size="small" :disabled="busy !== ''" @click="refreshMedia">刷新媒体</el-button></div>
          <p v-if="mediaLoadError" role="alert">{{ mediaLoadError }}</p>
          <p class="panel-copy">
            每个被引用媒体必须对应已 READY
            的资源，并填写面向读屏用户的替代文字。上传媒体可前往作品与图片管理。
          </p>
          <div v-for="mediaId in referencedMediaIds" :key="mediaId" class="catalog-grid">
            <label>媒体 ID<input :value="mediaId" disabled /></label>
            <img class="media-preview" :data-testid="`catalog-media-preview-${mediaId}`" :src="mediaThumbnail(mediaId)" :alt="draft.mediaAltTexts[mediaId] || '媒体预览'" loading="lazy" />
            <label
              >替代文字<input v-model="draft.mediaAltTexts[mediaId]" :data-testid="`catalog-media-alt-${mediaId}`" maxlength="120" required
            /></label>
          </div>
          <p v-if="!referencedMediaIds.length" class="empty-permissions">当前草稿没有媒体引用。</p>
        </fieldset>
        <fieldset class="catalog-section">
          <legend>首页精选</legend>
          <div class="catalog-grid">
            <label
              >精选业务 ID（每行一项）<textarea
                :value="listText(draft.featuredBusinessIds)"
                data-testid="catalog-featured-businesses"
                rows="3"
                @input="
                  draft!.featuredBusinessIds = lines(($event.target as HTMLTextAreaElement).value)
                "
              ></textarea></label
            ><label
              >精选套餐 ID（每行一项）<textarea
                :value="listText(draft.featuredPackageIds)"
                data-testid="catalog-featured-packages"
                rows="3"
                @input="
                  draft!.featuredPackageIds = lines(($event.target as HTMLTextAreaElement).value)
                "
              ></textarea></label
            ><div class="wide">
              <div class="media-toolbar">
                <span>从作品列表选择首页精选作品；服务器仍会复核发布与授权状态</span>
                <el-button data-testid="catalog-refresh-works" size="small" :disabled="busy !== ''" @click="refreshWorks">刷新作品</el-button>
              </div>
              <p v-if="worksLoadError" role="alert">{{ worksLoadError }}</p>
              <label>已管理作品（多选）<select multiple size="6" data-testid="catalog-featured-works-list" @change="setFeaturedWorks">
                <option v-for="work in works" :key="work.workId" :value="work.workId" :selected="draft.featuredWorkIds.includes(work.workId)">
                  {{ work.title || "（未命名）" }} · {{ work.workId }} · {{ work.publicationState }}
                </option>
              </select></label>
            </div>
            <label class="wide"
              >精选作品 ID（每行一项；仅用于列表外 ID 或调整顺序）<textarea
                :value="listText(draft.featuredWorkIds)"
                data-testid="catalog-featured-works"
                rows="3"
                @input="
                  draft!.featuredWorkIds = lines(($event.target as HTMLTextAreaElement).value)
                "
              ></textarea>
            </label>
          </div>
          <p class="panel-copy">
            作品 ID 可从作品管理页复制。服务端在预览与发布时重新检查 S2
            发布状态、授权有效期与当前公开指针；已撤回/过期作品不会进入首页。
          </p>
        </fieldset>
        <div class="catalog-actions">
          <el-button :disabled="!canEdit || busy !== ''" :loading="busy === 'save'" @click="save" data-testid="catalog-save"
            >保存草稿</el-button
          ><el-button :disabled="busy !== ''" :loading="busy === 'preview'" @click="checkPreview" data-testid="catalog-preview"
            >预览校验</el-button
          ><el-button
            type="primary"
            :disabled="!canPublish || busy !== '' || !preview?.publishable"
            :loading="busy === 'publish'"
            @click="publish"
            data-testid="catalog-publish"
            >发布</el-button
          ><el-button
            type="danger"
            plain
            :disabled="!canPublish || busy !== ''"
            :loading="busy === 'withdraw'"
            @click="withdraw"
            data-testid="catalog-withdraw"
            >下架当前目录</el-button
          ><el-button :disabled="busy !== ''" @click="load">重新载入</el-button>
          <el-button v-if="unknownAction" :disabled="busy !== ''" :loading="busy === 'resolve'" @click="resolveUnknownCommand" data-testid="catalog-resolve-unknown">
            查询未确认命令结果
          </el-button>
        </div>
        <p v-if="preview" role="status" data-testid="catalog-preview-status">
          发布资格：{{
            preview.publishable ? "通过" : `未通过（${preview.blockingReason ?? "资料不完整"}）`
          }}
        </p>
      </template>
      <div v-if="notice" class="form-feedback" role="status" data-testid="catalog-notice">
        {{ notice }}<small v-if="requestId">Request ID: {{ requestId }}</small>
      </div>
      <p v-if="!canEdit" class="form-feedback" role="alert">
        当前账号没有编辑能力；服务端仍会强制检查权限。
      </p>
      <p><router-link to="/works">继续管理作品与授权素材 →</router-link></p>
    </section>
  </main>
</template>

<style scoped>
.media-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 8px 0 12px;
}
.media-preview {
  max-width: 180px;
  max-height: 120px;
  object-fit: contain;
  border-radius: 8px;
  background: #f3f5f4;
}
.history-action {
  display: flex;
  align-items: end;
  gap: 8px;
}
.catalog-section {
  border: 1px solid #dfe5e7;
  border-radius: 12px;
  padding: 16px;
  margin: 18px 0;
  min-width: 0;
}
.catalog-section legend {
  font-weight: 700;
  padding: 0 8px;
}
.catalog-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}
.catalog-grid label {
  display: grid;
  gap: 5px;
  font-size: 0.9rem;
}
.catalog-grid .wide {
  grid-column: 1 / -1;
}
.catalog-grid input,
.catalog-grid textarea,
.catalog-grid select {
  width: 100%;
  border: 1px solid #cbd5d8;
  border-radius: 7px;
  padding: 9px;
  font: inherit;
}
.catalog-grid .check {
  display: flex;
  align-items: center;
  gap: 8px;
}
.catalog-grid .check input {
  width: auto;
}
.catalog-item {
  border-top: 1px solid #eef0f2;
  padding: 12px 0;
  margin-top: 8px;
}
.catalog-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 18px 0;
}
.minor {
  border: 0;
  background: none;
  color: #315f59;
  cursor: pointer;
  font: inherit;
  margin-left: 8px;
}
.danger {
  color: #a33;
}
</style>