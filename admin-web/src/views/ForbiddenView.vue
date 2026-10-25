<script setup lang="ts">
import { useRouter } from "vue-router";

const router = useRouter();
</script>

<template>
  <main class="message-shell">
    <section class="message-card">
      <p class="eyebrow">ACCESS CONTROL</p>
      <div class="message-code">403</div>
      <h1>沒有存取權限</h1>
      <p>目前帳戶沒有執行此操作的權限。有效工作階段不會因此被清除。</p>
      <el-button type="primary" @click="router.push('/')">返回身份首頁</el-button>
    </section>
  </main>
</template>
