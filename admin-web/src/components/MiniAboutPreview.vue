<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  name: string;
  englishName: string;
  tagline: string;
  description: string;
  phone: string;
  address: string;
  hours: string;
  team: Array<{ name: string; role: string; imageUrl: string }>;
  logoUrl?: string;
  coverUrl?: string;
}>();

const storyLines = computed(() =>
  props.description
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0),
);
const coverKicker = computed(() => (storyLines.value.length > 1 ? storyLines.value[0] : ""));
const coverTitles = computed(() =>
  storyLines.value.length > 1 ? storyLines.value.slice(1) : storyLines.value,
);
</script>

<template>
  <div class="mp-nav">关于我们</div>
  <div v-if="coverUrl" class="mp-cover">
    <img :src="coverUrl" alt="" />
    <div class="mp-cover-copy">
      <b>{{ name || "工作室" }}</b>
      <small v-if="coverKicker">{{ coverKicker }}</small>
      <strong v-for="line in coverTitles" :key="line">{{ line }}</strong>
    </div>
  </div>
  <div v-else class="mp-about-head">
    <div class="mp-logo">
      <img v-if="logoUrl" :src="logoUrl" alt="" />
      <span v-else>{{ name.trim().slice(0, 1) || "枝" }}</span>
    </div>
    <b>{{ name || "工作室" }}</b>
    <small v-if="englishName">{{ englishName }}</small>
    <p v-if="tagline">{{ tagline }}</p>
  </div>
  <div v-if="description && !coverUrl" class="mp-card">{{ description }}</div>
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
