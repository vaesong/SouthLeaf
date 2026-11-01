<script setup lang="ts">
import { onBeforeUnmount, watch } from "vue";

const props = defineProps<{ open: boolean; title: string; width?: number }>();
const emit = defineEmits<{ close: [] }>();

function onKey(event: KeyboardEvent) {
  if (event.key === "Escape" && props.open) emit("close");
}

watch(
  () => props.open,
  (open) => {
    if (open) window.addEventListener("keydown", onKey);
    else window.removeEventListener("keydown", onKey);
  },
  { immediate: true },
);

onBeforeUnmount(() => window.removeEventListener("keydown", onKey));
</script>

<template>
  <div v-if="open" class="scrim open" @click="emit('close')"></div>
  <aside
    v-if="open"
    class="drawer open"
    role="dialog"
    aria-modal="true"
    :aria-label="title"
    :style="width ? { width: `${width}px` } : undefined"
  >
    <div class="drawer-head">
      <h3>
        <slot name="heading">{{ title }}</slot>
      </h3>
      <button class="icon-btn" type="button" aria-label="关闭" @click="emit('close')">
        <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
      </button>
    </div>
    <div class="drawer-body">
      <slot />
    </div>
    <div v-if="$slots.footer" class="drawer-foot">
      <slot name="footer" />
    </div>
  </aside>
</template>
