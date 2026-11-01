<script setup lang="ts">
import { useRouter } from "vue-router";

const router = useRouter();
</script>

<template>
  <main class="login-screen">
    <section class="card card-pad" style="max-width: 480px">
      <h1>没有访问权限</h1>
      <p>当前账户没有执行此操作的权限。有效工作阶段不会因此被清除。</p>
      <button class="btn btn-primary" type="button" @click="router.push('/')">返回首页</button>
    </section>
  </main>
</template>
