<script setup lang="ts">
defineProps<{
  modelValue: boolean;
  label: string;
  testId?: string;
  disabled?: boolean;
}>();
const emit = defineEmits<{ "update:modelValue": [boolean] }>();
</script>

<template>
  <label class="switch-hit">
    <input
      type="checkbox"
      :checked="modelValue"
      :aria-label="label"
      :data-testid="testId"
      :disabled="disabled"
      @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
    />
    <span class="switch" :class="{ on: modelValue }"></span>
  </label>
</template>
