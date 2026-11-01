<script setup lang="ts">
import { inject } from "vue";
import { catalogStoreKey } from "../content/catalogStore";

defineProps<{ disabled?: boolean }>();

const injected = inject(catalogStoreKey);
if (!injected) throw new Error("CatalogStore was not provided");
const store = injected;

async function save() {
  await store.save();
}
</script>

<template>
  <button
    class="btn btn-secondary"
    type="button"
    :disabled="disabled || !store.dirty || store.state.busy !== ''"
    @click="store.discard()"
  >
    放弃修改
  </button>
  <button
    class="btn btn-primary"
    type="button"
    data-testid="catalog-save"
    :class="{ 'is-loading': store.state.busy === 'save' }"
    :disabled="disabled || !store.state.draft || store.state.busy !== ''"
    @click="save"
  >
    保存草稿
  </button>
</template>
