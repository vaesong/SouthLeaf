<script setup lang="ts">
const props = defineProps<{
  modelValue: string[];
  disabled?: boolean;
  max?: number;
  testId?: string;
}>();
const emit = defineEmits<{ "update:modelValue": [string[]] }>();

function commit(next: string[]) {
  emit("update:modelValue", props.max ? next.slice(0, props.max) : next);
}

function update(index: number, value: string) {
  const next = [...props.modelValue];
  next[index] = value;
  commit(next);
}

function remove(index: number) {
  commit(props.modelValue.filter((_, itemIndex) => itemIndex !== index));
}

function move(index: number, offset: number) {
  const next = [...props.modelValue];
  const target = index + offset;
  if (target < 0 || target >= next.length) return;
  const [item] = next.splice(index, 1);
  if (item !== undefined) next.splice(target, 0, item);
  commit(next);
}

function add() {
  if (props.disabled) return;
  if (props.max && props.modelValue.length >= props.max) return;
  commit([...props.modelValue, ""]);
}
</script>

<template>
  <div class="list-editor" :data-testid="testId">
    <div v-for="(item, index) in modelValue" :key="index" class="list-row">
      <input
        class="input"
        :value="item"
        :disabled="disabled"
        @input="update(index, ($event.target as HTMLInputElement).value)"
      />
      <button
        class="icon-btn"
        type="button"
        aria-label="上移"
        :disabled="disabled || index === 0"
        @click="move(index, -1)"
      >
        <svg viewBox="0 0 24 24"><path d="M6 14l6-6 6 6" /></svg>
      </button>
      <button
        class="icon-btn"
        type="button"
        aria-label="下移"
        :disabled="disabled || index === modelValue.length - 1"
        @click="move(index, 1)"
      >
        <svg viewBox="0 0 24 24"><path d="M6 10l6 6 6-6" /></svg>
      </button>
      <button
        class="icon-btn danger"
        type="button"
        aria-label="删除"
        :disabled="disabled"
        @click="remove(index)"
      >
        <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
      </button>
    </div>
    <button class="tag-add" type="button" :disabled="disabled" @click="add">+ 添加一条</button>
  </div>
</template>
