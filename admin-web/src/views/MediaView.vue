<script setup lang="ts">
import { computed, inject, onMounted, ref, shallowRef, watch } from "vue";
import { authStoreKey } from "../auth/store";
import { referencedMediaIds } from "../content/catalogDraft";
import { catalogStoreKey } from "../content/catalogStore";
import { mediaContentPath, newIdempotencyKey, type MediaView } from "../content/api";
import { requireContentApi } from "../content/inject";
import { searchMedia } from "../content/mediaSearch";
import { describeFailure } from "../content/messages";
import {
  rememberUploadedMedia,
  uploadFailureText,
  uploadFilesSequentially,
} from "../content/uploadFiles";
import StatusChip from "../components/StatusChip.vue";
import { mediaStatusLabel } from "../content/format";

const api = requireContentApi();
const auth = inject(authStoreKey);
const catalog = inject(catalogStoreKey);
const media = ref<MediaView[]>([]);
const loading = ref(true);
const busy = ref(false);
const notice = ref("");
const requestId = ref("");
const filter = ref("all");
const keyword = ref("");
const fileInput = ref<HTMLInputElement | null>(null);
const uploadIntent = shallowRef<{ key: string; file: File } | null>(null);
const canEdit = computed(() => auth?.state.identity?.permissions.includes("CONTENT_EDIT") ?? false);

function altOf(id: string) {
  return catalog?.state.draft?.mediaAltTexts[id] ?? "";
}

function referenced(id: string) {
  const draft = catalog?.state.draft;
  return !!draft && referencedMediaIds(draft).includes(id);
}

function setAlt(id: string, value: string) {
  const draft = catalog?.state.draft;
  if (!draft || !referenced(id)) return;
  draft.mediaAltTexts[id] = value;
}

const visible = computed(() =>
  media.value.filter((item) => {
    if (
      filter.value === "missing" &&
      !(referenced(item.mediaId) && altOf(item.mediaId).trim().length === 0)
    )
      return false;
    return true;
  }),
);

let searchToken = 0;

async function load() {
  const token = ++searchToken;
  loading.value = true;
  notice.value = "";
  try {
    if (catalog) await catalog.load();
    const rows = await api.listMedia();
    if (token !== searchToken) return;
    media.value = rows;
  } catch (error) {
    if (token !== searchToken) return;
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
  } finally {
    if (token === searchToken) loading.value = false;
  }
}

async function applySearch() {
  const query = keyword.value.trim();
  if (!query) {
    await load();
    return;
  }
  const token = ++searchToken;
  try {
    if (catalog && !catalog.state.draft) await catalog.load();
    const found = await searchMedia(
      api,
      query,
      catalog?.state.draft?.mediaAltTexts ?? {},
      {},
      media.value,
    );
    if (token !== searchToken) return;
    media.value = found;
  } catch (error) {
    if (token !== searchToken) return;
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
  }
}

async function upload(files: File[], reuse?: { key: string; file: File }) {
  busy.value = true;
  notice.value = "";
  const batch = reuse ? [reuse.file] : files;
  let replayed = false;
  const failure = await uploadFilesSequentially(
    batch,
    async (file, key) => {
      const result = await api.upload(file, file.name, key);
      replayed = result.replayed;
      media.value = rememberUploadedMedia(media.value, result.media);
    },
    (file) => (reuse && reuse.file === file ? reuse.key : newIdempotencyKey()),
  );
  if (failure) {
    const described = describeFailure(failure.error);
    notice.value = uploadFailureText(failure.file.name, failure.error);
    requestId.value = described.requestId;
    uploadIntent.value = described.unknown ? { key: failure.key, file: failure.file } : null;
  } else {
    uploadIntent.value = null;
    notice.value = batch.length === 1 && replayed ? "已确认先前的上传结果。" : "图片已处理并保存。";
  }
  busy.value = false;
  if (fileInput.value) fileInput.value.value = "";
}

function retryUpload() {
  const intent = uploadIntent.value;
  if (intent) void upload([intent.file], intent);
}

function onFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  input.value = "";
  uploadIntent.value = null;
  if (files.length > 0) void upload(files);
}

watch(keyword, () => {
  void applySearch();
});

onMounted(load);
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>图片库</h1>
        <p>作品、封面、团队照片和二维码都从这里选。补上描述后，记得在内容页保存。</p>
      </div>
      <div class="actions">
        <button
          class="btn btn-accent"
          type="button"
          :disabled="!canEdit || busy"
          @click="fileInput?.click()"
        >
          上传图片
        </button>
        <input
          ref="fileInput"
          data-testid="media-file"
          type="file"
          accept="image/jpeg,image/png"
          multiple
          hidden
          @change="onFile"
        />
      </div>
    </div>
    <div class="note" style="margin-bottom: 18px">
      <b>说明</b>
      <span
        >单张不超过 30MB，只接受 JPEG 或
        PNG。页面里用到的图片都要写描述。作品里的图片描述在编辑作品时填写。</span
      >
    </div>
    <p v-if="notice" class="notice-line" role="alert" data-testid="content-notice">
      {{ notice }}
      <button
        v-if="uploadIntent"
        class="btn btn-ghost btn-sm"
        type="button"
        data-testid="retry-upload"
        @click="retryUpload"
      >
        再试一次
      </button>
    </p>
    <div class="toolbar">
      <div class="seg">
        <button type="button" :class="{ on: filter === 'all' }" @click="filter = 'all'">
          全部 {{ media.length }}
        </button>
        <button type="button" :class="{ on: filter === 'missing' }" @click="filter = 'missing'">
          缺描述
        </button>
      </div>
      <div class="search" style="margin-left: auto">
        <input
          v-model="keyword"
          placeholder="按文件名或描述搜索"
          data-testid="media-library-search"
        />
      </div>
    </div>
    <p v-if="loading">正在读取图片…</p>
    <div v-else class="media-grid" data-testid="media-grid">
      <article v-for="item in visible" :key="item.mediaId" class="media">
        <div class="ph">
          <img :src="mediaContentPath(item.mediaId, 'thumbnail')" alt="" />
        </div>
        <div class="m">
          <span v-if="item.sourceFileName">{{ item.sourceFileName }}</span>
          {{ item.sourceWidth }}×{{ item.sourceHeight }}
          <StatusChip
            :tone="item.status === 'READY' ? 'live' : 'draft'"
            :text="mediaStatusLabel(item.status)"
          />
        </div>
        <div class="alt" :class="{ miss: !altOf(item.mediaId) }">
          <input
            v-if="referenced(item.mediaId)"
            class="input"
            maxlength="120"
            :value="altOf(item.mediaId)"
            :disabled="!canEdit"
            :data-testid="`catalog-media-alt-${item.mediaId}`"
            @input="setAlt(item.mediaId, ($event.target as HTMLInputElement).value)"
          />
          <span v-else>{{ altOf(item.mediaId) || "未在页面中使用" }}</span>
        </div>
      </article>
    </div>
  </div>
</template>
