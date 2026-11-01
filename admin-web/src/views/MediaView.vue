<script setup lang="ts">
import { computed, inject, onMounted, ref, shallowRef, watch } from "vue";
import { authStoreKey } from "../auth/store";
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
const media = ref<MediaView[]>([]);
const loading = ref(true);
const busy = ref(false);
const notice = ref("");
const requestId = ref("");
const keyword = ref("");
const fileInput = ref<HTMLInputElement | null>(null);
const uploadIntent = shallowRef<{ key: string; file: File } | null>(null);
const canEdit = computed(() => auth?.state.identity?.permissions.includes("CONTENT_EDIT") ?? false);

let searchToken = 0;

async function load() {
  const token = ++searchToken;
  loading.value = true;
  notice.value = "";
  try {
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
    const found = await searchMedia(api, query, media.value);
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
        <p>作品、封面、团队照片和二维码都从这里选。</p>
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
      <span>单张不超过 30MB，只接受 JPEG 或 PNG。</span>
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
      <span>全部 {{ media.length }}</span>
      <div class="search" style="margin-left: auto">
        <input v-model="keyword" placeholder="按文件名搜索" data-testid="media-library-search" />
      </div>
    </div>
    <p v-if="loading">正在读取图片…</p>
    <div v-else class="media-grid" data-testid="media-grid">
      <article v-for="item in media" :key="item.mediaId" class="media">
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
      </article>
    </div>
  </div>
</template>
