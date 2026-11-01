<script setup lang="ts">
defineProps<{
  name: string;
  englishName: string;
  tagline: string;
  description: string;
  phone: string;
  address: string;
  hours: string;
  team: Array<{ name: string; role: string; imageUrl: string }>;
  logoUrl?: string;
}>();
</script>

<template>
  <div class="mp-nav">关于我们</div>
  <div class="mp-about-head">
    <div class="mp-logo">
      <img v-if="logoUrl" :src="logoUrl" alt="" />
      <span v-else>{{ name.trim().slice(0, 1) || "枝" }}</span>
    </div>
    <b>{{ name || "工作室" }}</b>
    <small v-if="englishName">{{ englishName }}</small>
    <p v-if="tagline">{{ tagline }}</p>
  </div>
  <div v-if="description" class="mp-card">{{ description }}</div>
  <div v-if="team.length" class="mp-card">
    <b>我们的团队</b>
    <div v-for="member in team" :key="member.name" class="mp-team">
      <div class="ph"><img v-if="member.imageUrl" :src="member.imageUrl" alt="" /></div>
      <div>
        <b>{{ member.name }}</b
        ><br />{{ member.role }}
      </div>
    </div>
  </div>
  <div class="mp-card">
    <div v-if="phone" class="mp-kv">
      <span>电话</span><b>{{ phone }}</b>
    </div>
    <div v-if="address" class="mp-kv">
      <span>地址</span><b>{{ address }}</b>
    </div>
    <div v-if="hours" class="mp-kv">
      <span>营业</span><b>{{ hours }}</b>
    </div>
  </div>
</template>
