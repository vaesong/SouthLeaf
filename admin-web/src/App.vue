<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import AdminShell from "./AdminShell.vue";

const route = useRoute();
const framed = computed(() => route.meta.requiresAuth === true);
</script>

<template>
  <AdminShell v-if="framed">
    <router-view />
  </AdminShell>
  <router-view v-else />
</template>
