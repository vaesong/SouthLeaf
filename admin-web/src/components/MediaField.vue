<script setup lang="ts">
import { ref } from "vue";
import type { MediaView } from "../content/api";
import AppIcon from "./AppIcon.vue";
import MediaPicker from "./MediaPicker.vue";
import MediaThumb from "./MediaThumb.vue";

defineProps<{
  modelValue: string | null;
  media: MediaView[];
  disabled?: boolean;
  testId?: string;
  uploading?: boolean;
  notice?: string;
}>();

const emit = defineEmits<{
  "update:modelValue": [string | null];
  upload: [File[], string | null];
}>();

const open = ref(false);

function choose(id: string) {
  emit("update:modelValue", id);
  open.value = false;
}
</script>

<template>
  <div class="media-field">
    <button
      v-if="!modelValue"
      class="media-open"
      type="button"
      data-testid="media-field-empty"
      :disabled="disabled"
      @click="open = true"
    >
      <MediaThumb :media-id="null" />
    </button>
    <MediaThumb v-else :media-id="modelValue" :preview-id="modelValue" />
    <div class="media-field-actions">
      <button
        v-if="modelValue"
        class="btn btn-secondary btn-sm"
        type="button"
        :disabled="disabled"
        @click="open = true"
      >
        更换
      </button>
      <button
        class="btn btn-ghost btn-sm"
        type="button"
        :data-testid="testId"
        :disabled="disabled"
        @click="open = true"
      >
        从图片库选择
      </button>
      <button
        v-if="modelValue"
        class="icon-btn"
        type="button"
        aria-label="清除"
        :disabled="disabled"
        @click="emit('update:modelValue', null)"
      >
        <AppIcon name="close" />
      </button>
    </div>
    <MediaPicker
      :open="open"
      :media="media"
      :model-value="modelValue"
      :uploading="uploading"
      :notice="notice"
      @close="open = false"
      @choose="choose"
      @upload="(files, folderId) => emit('upload', files, folderId)"
    />
  </div>
</template>

<style scoped>
.media-field {
  display: grid;
  gap: 8px;
}
.media-field :deep(.ph),
.media-field :deep(.upload) {
  width: 120px;
  height: 80px;
  border-radius: 12px;
}
.media-field-actions {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 8px;
}
.media-open {
  width: fit-content;
  padding: 0;
  border: 0;
  background: none;
  cursor: pointer;
}
.media-open:disabled {
  cursor: not-allowed;
}
</style>
