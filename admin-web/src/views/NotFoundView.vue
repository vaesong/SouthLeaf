<script setup lang="ts">
import { useRouter } from "vue-router";

const router = useRouter();
</script>

<template>
  <main class="message-shell">
    <section class="message-card">
      <p class="eyebrow">PAGE NOT FOUND</p>
      <div class="message-code">404</div>
      <h1>找不到此頁面</h1>
      <p>此管理入口尚未提供該頁面。</p>
      <el-button type="primary" @click="router.push('/')">返回身份首頁</el-button>
    </section>
  </main>
</template>
