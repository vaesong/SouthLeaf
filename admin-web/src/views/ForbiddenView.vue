<script setup lang="ts">
import { useRouter } from "vue-router";

const router = useRouter();
</script>

<template>
  <main class="message-shell">
    <section class="message-card">
      <p class="eyebrow">ACCESS CONTROL</p>
      <div class="message-code">403</div>
      <h1>没有访问权限</h1>
      <p>当前账户没有执行此操作的权限。有效工作阶段不会因此被清除。</p>
      <el-button type="primary" @click="router.push('/')">返回首页</el-button>
    </section>
  </main>
</template>
