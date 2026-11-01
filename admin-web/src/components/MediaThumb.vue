<script setup lang="ts">
import { mediaContentPath } from "../content/api";

defineProps<{ mediaId: string | null; alt?: string; quiet?: boolean; previewId?: string }>();
</script>

<template>
  <div v-if="mediaId" class="ph">
    <img
      :src="mediaContentPath(mediaId, 'thumbnail')"
      :alt="alt || ''"
      :data-testid="previewId ? `catalog-media-preview-${previewId}` : undefined"
    />
  </div>
  <div v-else-if="quiet" class="ph quiet-ph" aria-hidden="true">
    <svg viewBox="0 0 24 24">
      <path d="M4 16l4-4 3 3 5-5 4 4" />
      <rect x="3" y="4" width="18" height="16" rx="2" />
    </svg>
  </div>
  <div v-else class="upload">选择图片</div>
</template>
