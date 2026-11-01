<script setup lang="ts">
import { ref } from "vue";
import { mediaContentPath, type MediaView } from "../content/api";
import SideDrawer from "./SideDrawer.vue";

defineProps<{
  open: boolean;
  media: MediaView[];
  modelValue?: string | null;
  uploading?: boolean;
}>();

const emit = defineEmits<{
  close: [];
  choose: [string];
  upload: [File];
}>();

const fileInput = ref<HTMLInputElement | null>(null);

function onFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (file) emit("upload", file);
  if (fileInput.value) fileInput.value.value = "";
}
</script>

<template>
  <SideDrawer :open="open" title="从图片库选择" @close="emit('close')">
    <div class="drawer-tools">
      <button
        class="btn btn-secondary btn-sm"
        type="button"
        :disabled="uploading"
        @click="fileInput?.click()"
      >
        {{ uploading ? "正在上传…" : "上传图片" }}
      </button>
      <input ref="fileInput" type="file" accept="image/jpeg,image/png" hidden @change="onFile" />
    </div>
    <div class="picker-grid">
      <button
        v-for="item in media"
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
          <span>{{ item.sourceWidth }}×{{ item.sourceHeight }}</span>
          <span v-if="item.status !== 'READY'">处理中</span>
        </div>
      </button>
    </div>
    <p v-if="media.length === 0" class="hint">图片库里还没有图片。</p>
    <template #footer>
      <button class="btn btn-secondary" type="button" @click="emit('close')">取消</button>
    </template>
  </SideDrawer>
</template>

<style scoped>
.drawer-tools {
  display: flex;
  justify-content: flex-end;
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
  padding: 8px 10px;
  font-size: 12px;
  color: var(--ink-2);
}
</style>
