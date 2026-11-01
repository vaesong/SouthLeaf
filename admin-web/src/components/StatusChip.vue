<script setup lang="ts">
defineProps<{ tone: "live" | "draft" | "off" | "warn" | "accent"; text: string }>();
</script>

<template>
  <span class="chip" :class="`chip-${tone}`"><i></i>{{ text }}</span>
</template>
