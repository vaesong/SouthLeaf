<script setup lang="ts">
defineProps<{
  open: boolean;
  title: string;
  message: string;
  confirmText: string;
  danger?: boolean;
  confirmDisabled?: boolean;
  messageTestId?: string;
}>();
const emit = defineEmits<{ confirm: []; cancel: [] }>();
</script>

<template>
  <div v-if="open" class="scrim open" @click="emit('cancel')"></div>
  <div v-if="open" class="modal open" role="dialog" aria-modal="true" :aria-label="title">
    <div class="m-ico">
      <svg viewBox="0 0 24 24">
        <path
          d="M12 9v4M12 17h.01M10.3 3.9 2 18a2 2 0 0 0 1.7 3h16.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"
        />
      </svg>
    </div>
    <h3>{{ title }}</h3>
    <p :data-testid="messageTestId">{{ message }}</p>
    <slot />
    <div class="row">
      <button class="btn btn-secondary" type="button" @click="emit('cancel')">取消</button>
      <button
        class="btn"
        :class="danger ? 'btn-danger' : 'btn-primary'"
        type="button"
        :disabled="confirmDisabled"
        @click="emit('confirm')"
      >
        {{ confirmText }}
      </button>
    </div>
  </div>
</template>
