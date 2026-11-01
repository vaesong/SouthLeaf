<script setup lang="ts">
import { computed, inject, ref, watch } from "vue";
import { mediaContentPath, type MediaView } from "../content/api";
import { catalogStoreKey } from "../content/catalogStore";
import { contentApiKey } from "../content/inject";
import { searchMedia } from "../content/mediaSearch";
import SideDrawer from "./SideDrawer.vue";

const props = defineProps<{
  open: boolean;
  media: MediaView[];
  modelValue?: string | null;
  uploading?: boolean;
  extraAlts?: Record<string, string>;
  notice?: string;
}>();

const emit = defineEmits<{
  close: [];
  choose: [string];
  upload: [File[]];
}>();

const api = inject(contentApiKey);
const catalog = inject(catalogStoreKey);
const fileInput = ref<HTMLInputElement | null>(null);
const keyword = ref("");
const results = ref<MediaView[] | null>(null);
const searching = ref(false);
let searchSerial = 0;

const shown = computed(() => (keyword.value.trim() ? (results.value ?? []) : props.media));

watch(
  () =>
    [
      props.open,
      keyword.value,
      props.media,
      props.extraAlts,
      catalog?.state.draft?.mediaAltTexts,
    ] as const,
  () => {
    void refresh();
  },
);

async function refresh() {
  const query = keyword.value.trim();
  const token = ++searchSerial;
  if (!props.open || !query) {
    results.value = null;
    searching.value = false;
    return;
  }
  if (!api) {
    results.value = [];
    searching.value = false;
    return;
  }
  searching.value = true;
  try {
    const found = await searchMedia(
      api,
      query,
      catalog?.state.draft?.mediaAltTexts ?? {},
      props.extraAlts ?? {},
      props.media,
    );
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
  if (files.length > 0) emit("upload", files);
}
</script>

<template>
  <SideDrawer :open="open" title="从图片库选择" @close="emit('close')">
    <div class="drawer-tools">
      <input
        v-model="keyword"
        class="input"
        type="search"
        placeholder="按文件名或描述搜索"
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
    <div class="picker-grid">
      <button
        v-for="item in shown"
        :key="item.mediaId"
        type="button"
        class="pick"
        :class="{ on: item.mediaId === modelValue, processing: item.status !== 'READY' }"
        data-testid="media-picker-item"
        :data-media-id="item.mediaId"
        :data-processing="item.status === 'READY' ? undefined : 'true'"
        :disabled="item.status !== 'READY'"
        @click="emit('choose', item.mediaId)"
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
    <p v-else-if="shown.length === 0" class="hint">
      {{ keyword.trim() ? "没有匹配的图片。" : "图片库里还没有图片。" }}
    </p>
    <template #footer>
      <button class="btn btn-secondary" type="button" @click="emit('close')">取消</button>
    </template>
  </SideDrawer>
</template>

<style scoped>
.drawer-tools {
  display: flex;
  align-items: center;
  gap: 8px;
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
