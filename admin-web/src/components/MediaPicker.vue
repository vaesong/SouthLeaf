<script setup lang="ts">
import { computed, inject, ref, watch } from "vue";
import { mediaContentPath, type MediaFolder, type MediaView } from "../content/api";
import { contentApiKey } from "../content/inject";
import { searchMedia } from "../content/mediaSearch";
import SideDrawer from "./SideDrawer.vue";

const props = defineProps<{
  open: boolean;
  media: MediaView[];
  modelValue?: string | null;
  uploading?: boolean;
  notice?: string;
  multiple?: boolean;
}>();

const emit = defineEmits<{
  close: [];
  choose: [string];
  upload: [File[], string | null];
}>();

const api = inject(contentApiKey);
const fileInput = ref<HTMLInputElement | null>(null);
const keyword = ref("");
const results = ref<MediaView[] | null>(null);
const folders = ref<MediaFolder[]>([]);
const folderId = ref<string | null>(null);
const loaded = ref<MediaView[] | null>(null);
const searching = ref(false);
const picked = ref<string[]>([]);
let searchSerial = 0;

const shown = computed(() =>
  keyword.value.trim() ? (results.value ?? []) : (loaded.value ?? props.media),
);
const childFolders = computed(() =>
  folders.value.filter((folder) => folder.parentId === folderId.value),
);

watch(
  () => [props.open, keyword.value, props.media, folderId.value] as const,
  () => {
    if (!props.open) picked.value = [];
    void refresh();
  },
);

function onPick(id: string) {
  if (!props.multiple) {
    emit("choose", id);
    return;
  }
  picked.value = picked.value.includes(id)
    ? picked.value.filter((item) => item !== id)
    : [...picked.value, id];
}

function confirmPicks() {
  for (const id of picked.value) emit("choose", id);
  picked.value = [];
  emit("close");
}

async function loadBrowser() {
  if (!props.open || !api?.listFolders) {
    loaded.value = null;
    folders.value = [];
    return;
  }
  folders.value = await api.listFolders();
  loaded.value = await api.listMedia("", folderId.value);
}

function openFolder(id: string | null) {
  keyword.value = "";
  folderId.value = id;
}

async function refresh() {
  const query = keyword.value.trim();
  const token = ++searchSerial;
  if (!props.open || !query) {
    results.value = null;
    searching.value = false;
    if (!query) void loadBrowser();
    return;
  }
  if (!api) {
    results.value = [];
    searching.value = false;
    return;
  }
  searching.value = true;
  try {
    const found = await searchMedia(api, query, props.media);
    if (token !== searchSerial) return;
    results.value = found;
  } catch {
    if (token !== searchSerial) return;
    results.value = [];
  } finally {
    if (token === searchSerial) searching.value = false;
  }
}

function onFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  input.value = "";
  if (files.length > 0) emit("upload", files, folderId.value);
}
</script>

<template>
  <SideDrawer :open="open" title="从图片库选择" @close="emit('close')">
    <div class="drawer-tools">
      <input
        v-model="keyword"
        class="input"
        type="search"
        placeholder="按文件名搜索"
        data-testid="media-picker-search"
      />
      <button
        class="btn btn-secondary btn-sm"
        type="button"
        :disabled="uploading"
        @click="fileInput?.click()"
      >
        {{ uploading ? "正在上传…" : "上传图片" }}
      </button>
      <input
        ref="fileInput"
        data-testid="media-picker-file"
        type="file"
        accept="image/jpeg,image/png"
        multiple
        hidden
        @change="onFile"
      />
    </div>
    <div v-if="folders.length" class="folder-row">
      <button type="button" class="btn btn-ghost btn-sm" @click="openFolder(null)">图片库</button>
      <button
        v-if="folderId"
        type="button"
        class="btn btn-ghost btn-sm"
        @click="openFolder(folders.find((folder) => folder.folderId === folderId)?.parentId ?? null)"
      >
        向上
      </button>
    </div>
    <div class="picker-grid">
      <button
        v-for="folder in keyword.trim() ? [] : childFolders"
        :key="folder.folderId"
        type="button"
        class="pick folder-pick"
        @click="openFolder(folder.folderId)"
      >
        <span class="folder-mark" aria-hidden="true"></span>
        <div class="t"><span>{{ folder.name }}</span></div>
      </button>
      <button
        v-for="item in shown"
        :key="item.mediaId"
        type="button"
        class="pick"
        :class="{
          on: props.multiple ? picked.includes(item.mediaId) : item.mediaId === modelValue,
          processing: item.status !== 'READY',
        }"
        data-testid="media-picker-item"
        :data-media-id="item.mediaId"
        :data-processing="item.status === 'READY' ? undefined : 'true'"
        :disabled="item.status !== 'READY'"
        @click="onPick(item.mediaId)"
      >
        <div class="ph">
          <img :src="mediaContentPath(item.mediaId, 'thumbnail')" alt="" />
        </div>
        <div class="t">
          <span>{{ item.sourceFileName || `${item.sourceWidth}×${item.sourceHeight}` }}</span>
          <span v-if="item.status !== 'READY'">处理中</span>
        </div>
      </button>
    </div>
    <p v-if="notice" class="hint" role="alert" data-testid="media-picker-notice">{{ notice }}</p>
    <p v-if="searching" class="hint">正在查找…</p>
    <p v-else-if="shown.length === 0 && (keyword.trim() || childFolders.length === 0)" class="hint">
      {{ keyword.trim() ? "没有匹配的图片。" : "这个文件夹是空的。" }}
    </p>
    <template #footer>
      <button class="btn btn-secondary" type="button" @click="emit('close')">取消</button>
      <button
        v-if="multiple"
        class="btn btn-accent"
        type="button"
        data-testid="media-picker-add"
        :disabled="picked.length === 0"
        @click="confirmPicks"
      >
        加入所选
      </button>
    </template>
  </SideDrawer>
</template>

<style scoped>
.drawer-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}
.folder-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.folder-mark {
  display: block;
  width: 64px;
  height: 48px;
  margin: 22px auto 0;
  border-radius: 8px 8px 10px 10px;
  background: linear-gradient(#f3d7a4, #e2b56a);
  box-shadow: inset 0 8px 0 #f8e7c4;
}
.drawer-tools .input {
  flex: 1;
}
.picker-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.pick {
  padding: 0;
  text-align: left;
}
.pick.processing {
  filter: grayscale(1);
  opacity: 0.55;
}
.pick .ph {
  height: 92px;
}
.pick .t {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  font-size: 12px;
  color: var(--ink-2);
}
.pick .t span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
