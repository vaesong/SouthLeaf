<script setup lang="ts">
import { nextTick, ref } from "vue";

const props = defineProps<{
  modelValue: string[];
  disabled?: boolean;
  max?: number;
  testId?: string;
}>();
const emit = defineEmits<{ "update:modelValue": [string[]] }>();

const adding = ref(false);
const text = ref("");
const input = ref<HTMLInputElement | null>(null);

function remove(index: number) {
  emit(
    "update:modelValue",
    props.modelValue.filter((_, itemIndex) => itemIndex !== index),
  );
}

async function startAdd() {
  if (props.disabled) return;
  if (props.max && props.modelValue.length >= props.max) return;
  adding.value = true;
  text.value = "";
  await nextTick();
  input.value?.focus();
}

function commit() {
  const value = text.value.trim();
  adding.value = false;
  text.value = "";
  if (!value) return;
  if (props.modelValue.includes(value)) return;
  if (props.max && props.modelValue.length >= props.max) return;
  emit("update:modelValue", [...props.modelValue, value]);
}

function onKey(event: KeyboardEvent) {
  if (event.key === "Enter") {
    event.preventDefault();
    commit();
  } else if (event.key === "Escape") {
    adding.value = false;
    text.value = "";
  }
}
</script>

<template>
  <div class="tags" :data-testid="testId">
    <span v-for="(item, index) in modelValue" :key="`${index}-${item}`" class="tag">
      {{ item }}
      <button type="button" :disabled="disabled" aria-label="移除" @click="remove(index)">✕</button>
    </span>
    <input
      v-if="adding"
      ref="input"
      v-model="text"
      class="input"
      style="width: 160px; height: 30px"
      @keydown="onKey"
      @blur="commit"
    />
    <button v-else class="tag-add" type="button" :disabled="disabled" @click="startAdd">
      + 添加
    </button>
  </div>
</template>
