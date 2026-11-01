<script setup lang="ts">
import { useRouter } from "vue-router";

const router = useRouter();
</script>

<template>
  <main class="login-screen">
    <section class="card card-pad" style="max-width: 480px">
      <h1>找不到此页面</h1>
      <p>这个地址没有对应的后台页面。</p>
      <button class="btn btn-primary" type="button" @click="router.push('/')">返回首页</button>
    </section>
  </main>
</template>
