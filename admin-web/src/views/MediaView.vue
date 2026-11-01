<script setup lang="ts">
import { computed, inject, onMounted, ref, shallowRef, watch } from "vue";
import { authStoreKey } from "../auth/store";
import {
  mediaContentPath,
  newIdempotencyKey,
  type MediaFolder,
  type MediaView,
} from "../content/api";
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
const folders = ref<MediaFolder[]>([]);
const folderId = ref<string | null>(null);
const folderName = ref("");
const loading = ref(true);
const busy = ref(false);
const notice = ref("");
const requestId = ref("");
const keyword = ref("");
const fileInput = ref<HTMLInputElement | null>(null);
const uploadIntent = shallowRef<{ key: string; file: File } | null>(null);
const canEdit = computed(() => auth?.state.identity?.permissions.includes("CONTENT_EDIT") ?? false);
const childFolders = computed(() =>
  folders.value.filter((folder) => folder.parentId === folderId.value),
);
const crumbs = computed(() => {
  const path: MediaFolder[] = [];
  let cursor = folderId.value;
  const guard = new Set<string>();
  while (cursor && !guard.has(cursor)) {
    guard.add(cursor);
    const found = folders.value.find((folder) => folder.folderId === cursor);
    if (!found) break;
    path.unshift(found);
    cursor = found.parentId;
  }
  return path;
});

let searchToken = 0;

async function load() {
  const token = ++searchToken;
  loading.value = true;
  notice.value = "";
  try {
    const [rows, dirs] = await Promise.all([
      api.listMedia("", folderId.value),
      api.listFolders(),
    ]);
    if (token !== searchToken) return;
    media.value = rows;
    folders.value = dirs;
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

function openFolder(id: string | null) {
  folderId.value = id;
  keyword.value = "";
  void load();
}

async function createFolder() {
  const name = folderName.value.trim();
  if (!name) return;
  busy.value = true;
  notice.value = "";
  try {
    await api.createFolder(name, folderId.value);
    folderName.value = "";
    await load();
  } catch (error) {
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
  } finally {
    busy.value = false;
  }
}

async function removeFolder(id: string) {
  busy.value = true;
  notice.value = "";
  try {
    await api.deleteFolder(id);
    await load();
  } catch (error) {
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
  } finally {
    busy.value = false;
  }
}

async function removeMedia(id: string) {
  busy.value = true;
  notice.value = "";
  try {
    await api.deleteMedia(id);
    media.value = media.value.filter((item) => item.mediaId !== id);
  } catch (error) {
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
  } finally {
    busy.value = false;
  }
}

async function moveMedia(id: string, target: string) {
  busy.value = true;
  notice.value = "";
  try {
    await api.moveMedia(id, target || null);
    await load();
  } catch (error) {
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
  } finally {
    busy.value = false;
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
      const result = await api.upload(file, file.name, key, folderId.value);
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
        <p>按文件夹找图。上传会放进当前文件夹。正在被页面或作品使用的图片不能删除。</p>
      </div>
      <div class="actions">
        <input
          v-model="folderName"
          class="input"
          maxlength="40"
          placeholder="新文件夹名称"
          :disabled="!canEdit || busy"
        />
        <button
          class="btn btn-secondary"
          type="button"
          :disabled="!canEdit || busy || !folderName.trim()"
          @click="createFolder"
        >
          新建文件夹
        </button>
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
      <span>单张不超过 30MB，只接受 JPEG 或 PNG。文件夹最多三层。</span>
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
      <button type="button" class="btn btn-ghost btn-sm" @click="openFolder(null)">全部图片</button>
      <button
        v-for="crumb in crumbs"
        :key="crumb.folderId"
        type="button"
        class="btn btn-ghost btn-sm"
        @click="openFolder(crumb.folderId)"
      >
        {{ crumb.name }}
      </button>
      <div class="search" style="margin-left: auto">
        <input v-model="keyword" placeholder="按文件名搜索" data-testid="media-library-search" />
      </div>
    </div>
    <p v-if="loading">正在读取图片…</p>
    <div v-else>
      <div class="folder-row">
        <button
          v-for="folder in childFolders"
          :key="folder.folderId"
          type="button"
          class="btn btn-secondary btn-sm"
          @click="openFolder(folder.folderId)"
        >
          {{ folder.name }}
        </button>
        <button
          v-for="folder in childFolders"
          :key="`${folder.folderId}-delete`"
          type="button"
          class="btn btn-ghost btn-sm"
          :disabled="!canEdit || busy"
          @click="removeFolder(folder.folderId)"
        >
          删除「{{ folder.name }}」
        </button>
      </div>
      <div class="media-grid" data-testid="media-grid">
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
          <div class="m">
            <select
              class="select"
              :disabled="!canEdit || busy"
              :value="item.folderId ?? ''"
              @change="moveMedia(item.mediaId, ($event.target as HTMLSelectElement).value)"
            >
              <option value="">未分类</option>
              <option v-for="folder in folders" :key="folder.folderId" :value="folder.folderId">
                {{ folder.name }}
              </option>
            </select>
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              :disabled="!canEdit || busy"
              @click="removeMedia(item.mediaId)"
            >
              删除
            </button>
          </div>
        </article>
      </div>
    </div>
  </div>
</template>

<style scoped>
.folder-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
</style>
