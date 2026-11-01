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
import ConfirmDialog from "../components/ConfirmDialog.vue";
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
const selected = ref<{ kind: "folder" | "image"; id: string }[]>([]);
let anchor: { kind: "folder" | "image"; id: string } | null = null;
const deleteOpen = ref(false);
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
  selected.value = [];
  anchor = null;
  void load();
}

function visibleItems() {
  return [
    ...childFolders.value.map((folder) => ({ kind: "folder" as const, id: folder.folderId })),
    ...media.value.map((item) => ({ kind: "image" as const, id: item.mediaId })),
  ];
}

function sameItem(
  left: { kind: "folder" | "image"; id: string },
  right: { kind: "folder" | "image"; id: string },
) {
  return left.kind === right.kind && left.id === right.id;
}

function isSelected(kind: "folder" | "image", id: string) {
  return selected.value.some((item) => item.kind === kind && item.id === id);
}

function select(kind: "folder" | "image", id: string, event: MouseEvent) {
  const item = { kind, id };
  const items = visibleItems();
  if (event.shiftKey && anchor) {
    const start = items.findIndex((entry) => sameItem(entry, anchor!));
    const end = items.findIndex((entry) => sameItem(entry, item));
    if (start >= 0 && end >= 0) {
      const [from, to] = start < end ? [start, end] : [end, start];
      selected.value = items.slice(from, to + 1);
      return;
    }
  }
  if (event.ctrlKey || event.metaKey) {
    selected.value = isSelected(kind, id)
      ? selected.value.filter((entry) => !sameItem(entry, item))
      : [...selected.value, item];
    anchor = item;
    return;
  }
  selected.value = [item];
  anchor = item;
}

const deleteMessage = computed(() => {
  const count = selected.value.length;
  if (count <= 1) {
    const current = selected.value[0];
    if (!current) return "";
    const name =
      current.kind === "folder"
        ? (folders.value.find((folder) => folder.folderId === current.id)?.name ?? "文件夹")
        : media.value.find((item) => item.mediaId === current.id)?.sourceFileName || "这张图片";
    return `删除「${name}」？文件夹里还有内容时不能删除。正在被页面或作品使用的图片也不能删除。`;
  }
  return `删除选中的 ${count} 项？文件夹里还有内容时不能删除。正在被页面或作品使用的图片也不能删除。`;
});

function askDelete() {
  if (!selected.value.length) return;
  deleteOpen.value = true;
}

async function confirmDelete() {
  const items = [...selected.value];
  deleteOpen.value = false;
  if (!items.length) return;
  busy.value = true;
  notice.value = "";
  const failed: string[] = [];
  for (const item of items) {
    try {
      if (item.kind === "folder") await api.deleteFolder(item.id);
      else await api.deleteMedia(item.id);
    } catch (error) {
      failed.push(describeFailure(error).text);
    }
  }
  selected.value = [];
  anchor = null;
  await load();
  if (failed.length) notice.value = failed[0] ?? "";
  busy.value = false;
}

function onImageDragStart(event: DragEvent, mediaId: string) {
  const ids = isSelected("image", mediaId)
    ? selected.value.filter((item) => item.kind === "image").map((item) => item.id)
    : [mediaId];
  event.dataTransfer?.setData("text/media-id", ids.join("\n"));
  event.dataTransfer?.setData("text/plain", ids.join("\n"));
}

function onFolderDragOver(event: DragEvent) {
  if (event.dataTransfer?.types.includes("text/media-id")) event.preventDefault();
}

async function onFolderDrop(event: DragEvent, target: string | null) {
  const raw = event.dataTransfer?.getData("text/media-id") ?? "";
  const ids = raw.split("\n").filter((id) => id && id !== target);
  for (const id of ids) await moveMedia(id, target ?? "");
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
        <p>像文件夹一样管理。单击选中，按住 Ctrl 或 Shift 可以多选，再按删除。双击打开文件夹，把图片拖进去即可归类。</p>
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
        <button
          class="btn btn-danger-ghost"
          type="button"
          :disabled="!canEdit || busy || selected.length === 0"
          @click="askDelete"
        >
          删除
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
      <button
        type="button"
        class="btn btn-ghost btn-sm"
        :disabled="!folderId"
        @dragover="onFolderDragOver"
        @drop="onFolderDrop($event, crumbs.at(-2)?.folderId ?? null)"
        @click="openFolder(crumbs.at(-2)?.folderId ?? null)"
      >
        向上
      </button>
      <button
        type="button"
        class="btn btn-ghost btn-sm"
        @dragover="onFolderDragOver"
        @drop="onFolderDrop($event, null)"
        @click="openFolder(null)"
      >
        图片库
      </button>
      <button
        v-for="crumb in crumbs"
        :key="crumb.folderId"
        type="button"
        class="btn btn-ghost btn-sm"
        @dragover="onFolderDragOver"
        @drop="onFolderDrop($event, crumb.folderId)"
        @click="openFolder(crumb.folderId)"
      >
        {{ crumb.name }}
      </button>
      <div class="search" style="margin-left: auto">
        <input v-model="keyword" placeholder="按文件名搜索" data-testid="media-library-search" />
      </div>
    </div>
    <p v-if="loading">正在读取图片…</p>
    <div v-else class="explorer" data-testid="media-grid" @click="selected = []">
      <button
        v-for="folder in childFolders"
        :key="folder.folderId"
        type="button"
        class="tile"
        :class="{ on: isSelected('folder', folder.folderId) }"
        @click.stop="select('folder', folder.folderId, $event)"
        @dblclick.stop="openFolder(folder.folderId)"
        @dragover="onFolderDragOver"
        @drop.stop="onFolderDrop($event, folder.folderId)"
      >
        <span class="folder-mark" aria-hidden="true"></span>
        <b>{{ folder.name }}</b>
      </button>
      <article
        v-for="item in media"
        :key="item.mediaId"
        class="tile"
        :class="{ on: isSelected('image', item.mediaId) }"
        draggable="true"
        @click.stop="select('image', item.mediaId, $event)"
        @dragstart="onImageDragStart($event, item.mediaId)"
      >
        <div class="ph">
          <img :src="mediaContentPath(item.mediaId, 'thumbnail')" alt="" />
        </div>
        <b>{{ item.sourceFileName || "未命名图片" }}</b>
        <StatusChip
          :tone="item.status === 'READY' ? 'live' : 'draft'"
          :text="mediaStatusLabel(item.status)"
        />
      </article>
      <p v-if="childFolders.length === 0 && media.length === 0" class="hint">这个文件夹是空的。</p>
    </div>
    <ConfirmDialog
      :open="deleteOpen"
      title="删除"
      :message="deleteMessage"
      confirm-text="删除"
      danger
      @cancel="deleteOpen = false"
      @confirm="confirmDelete"
    />
  </div>
</template>

<style scoped>
.explorer {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 12px;
  min-height: 240px;
}
.tile {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 12px;
  border: 1px solid transparent;
  border-radius: 14px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: center;
}
.tile b {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 560;
}
.tile.on {
  background: #fff8f2;
  border-color: var(--accent);
}
.tile .ph {
  width: 100%;
  height: 108px;
  border-radius: 10px;
}
.folder-mark {
  width: 72px;
  height: 56px;
  border-radius: 8px 8px 10px 10px;
  background: linear-gradient(#f3d7a4, #e2b56a);
  box-shadow: inset 0 8px 0 #f8e7c4;
}
</style>
