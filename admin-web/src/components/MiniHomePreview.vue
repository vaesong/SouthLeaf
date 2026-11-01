<script setup lang="ts">
defineProps<{
  title: string;
  heading: string;
  businesses: Array<{ name: string; imageUrl: string }>;
  works: Array<{ title: string; imageUrl: string }>;
  address: string;
  hero?: { title: string; subtitle: string; action: string; imageUrl: string } | null;
}>();
</script>

<template>
  <div class="mp-nav">{{ title || "首页" }}</div>
  <div v-if="hero" class="mp-swiper ph">
    <img
      v-if="hero.imageUrl"
      :src="hero.imageUrl"
      alt=""
      style="position: absolute; inset: 0; height: 100%"
    />
    <div class="cap">
      <small v-if="hero.subtitle">{{ hero.subtitle }}</small>
      <b>{{ hero.title || "未填写标题" }}</b>
      <span v-if="hero.action">{{ hero.action }}</span>
    </div>
  </div>
  <div v-else-if="works[0]" class="mp-swiper ph">
    <img
      v-if="works[0].imageUrl"
      :src="works[0].imageUrl"
      alt=""
      style="position: absolute; inset: 0; height: 100%"
    />
    <div class="cap">
      <b>{{ works[0].title || "精选作品" }}</b>
    </div>
  </div>
  <div class="mp-sec">
    <h4>{{ heading || "精选业务" }}</h4>
    <div class="mp-biz">
      <div v-for="item in businesses.slice(0, 3)" :key="item.name">
        <div class="ph"><img v-if="item.imageUrl" :src="item.imageUrl" alt="" /></div>
        {{ item.name }}
      </div>
    </div>
  </div>
  <div class="mp-sec">
    <h4>精选作品</h4>
    <div class="mp-works">
      <div v-for="item in works.slice(0, 4)" :key="item.title" class="ph">
        <img v-if="item.imageUrl" :src="item.imageUrl" alt="" />
      </div>
    </div>
  </div>
  <div v-if="address" class="mp-studio"><b>来工作室坐坐</b>{{ address }}</div>
</template>
