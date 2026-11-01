<script setup lang="ts">
import { computed, inject, onMounted, ref, shallowRef } from "vue";
import { authStoreKey } from "../auth/store";
import { referencedMediaIds } from "../content/catalogDraft";
import { catalogStoreKey } from "../content/catalogStore";
import { mediaContentPath, newIdempotencyKey, type MediaView } from "../content/api";
import { requireContentApi } from "../content/inject";
import { describeFailure } from "../content/messages";
import StatusChip from "../components/StatusChip.vue";
import { mediaStatusLabel } from "../content/format";

const api = requireContentApi();
const auth = inject(authStoreKey);
const catalog = inject(catalogStoreKey);
const media = ref<MediaView[]>([]);
const loading = ref(true);
const busy = ref(false);
const notice = ref("");
const requestId = ref("");
const filter = ref("all");
const keyword = ref("");
const fileInput = ref<HTMLInputElement | null>(null);
const uploadIntent = shallowRef<{ key: string; file: File } | null>(null);
const canEdit = computed(() => auth?.state.identity?.permissions.includes("CONTENT_EDIT") ?? false);

function altOf(id: string) {
  return catalog?.state.draft?.mediaAltTexts[id] ?? "";
}

function referenced(id: string) {
  const draft = catalog?.state.draft;
  return !!draft && referencedMediaIds(draft).includes(id);
}

function setAlt(id: string, value: string) {
  const draft = catalog?.state.draft;
  if (!draft || !referenced(id)) return;
  draft.mediaAltTexts[id] = value;
}

const visible = computed(() =>
  media.value.filter((item) => {
    const alt = altOf(item.mediaId);
    if (filter.value === "missing" && !(referenced(item.mediaId) && alt.trim().length === 0))
      return false;
    if (keyword.value && !alt.includes(keyword.value)) return false;
    return true;
  }),
);

async function load() {
  loading.value = true;
  notice.value = "";
  try {
    if (catalog) await catalog.load();
    media.value = await api.listMedia();
  } catch (error) {
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
  } finally {
    loading.value = false;
  }
}

async function upload(file: File) {
  busy.value = true;
  notice.value = "";
  if (!uploadIntent.value || uploadIntent.value.file !== file)
    uploadIntent.value = { key: newIdempotencyKey(), file };
  try {
    const result = await api.upload(file, file.name, uploadIntent.value.key);
    uploadIntent.value = null;
    media.value = [
      result.media,
      ...media.value.filter((item) => item.mediaId !== result.media.mediaId),
    ];
    notice.value = result.replayed ? "已确认先前的上传结果。" : "图片已处理并保存。";
  } catch (error) {
    const failure = describeFailure(error);
    notice.value = failure.text;
    requestId.value = failure.requestId;
    if (!failure.unknown) uploadIntent.value = null;
  } finally {
    busy.value = false;
    if (fileInput.value) fileInput.value.value = "";
  }
}

function onFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (file) void upload(file);
}

onMounted(load);
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>图片库</h1>
        <p>作品、封面、团队照片和二维码都从这里选。补上描述后，记得在内容页保存。</p>
      </div>
      <div class="actions">
        <button
          class="btn btn-accent"
          type="button"
          :disabled="!canEdit || busy"
          @click="fileInput?.click()"
        >
          上传图片
        </button>
        <input
          ref="fileInput"
          data-testid="media-file"
          type="file"
          accept="image/jpeg,image/png"
          hidden
          @change="onFile"
        />
      </div>
    </div>
    <div class="note" style="margin-bottom: 18px">
      <b>说明</b>
      <span
        >单张不超过 4MB，只接受 JPEG 或
        PNG。页面里用到的图片都要写描述。作品里的图片描述在编辑作品时填写。</span
      >
    </div>
    <p v-if="notice" class="notice-line" role="alert" data-testid="content-notice">
      {{ notice }}
      <button
        v-if="uploadIntent"
        class="btn btn-ghost btn-sm"
        type="button"
        data-testid="retry-upload"
        @click="upload(uploadIntent.file)"
      >
        再试一次
      </button>
    </p>
    <div class="toolbar">
      <div class="seg">
        <button type="button" :class="{ on: filter === 'all' }" @click="filter = 'all'">
          全部 {{ media.length }}
        </button>
        <button type="button" :class="{ on: filter === 'missing' }" @click="filter = 'missing'">
          缺描述
        </button>
      </div>
      <div class="search" style="margin-left: auto">
        <input v-model="keyword" placeholder="按描述搜索" />
      </div>
    </div>
    <p v-if="loading">正在读取图片…</p>
    <div v-else class="media-grid" data-testid="media-grid">
      <article v-for="item in visible" :key="item.mediaId" class="media">
        <div class="ph">
          <img :src="mediaContentPath(item.mediaId, 'thumbnail')" alt="" />
        </div>
        <div class="m">
          {{ item.sourceWidth }}×{{ item.sourceHeight }}
          <StatusChip
            :tone="item.status === 'READY' ? 'live' : 'draft'"
            :text="mediaStatusLabel(item.status)"
          />
        </div>
        <div class="alt" :class="{ miss: !altOf(item.mediaId) }">
          <input
            v-if="referenced(item.mediaId)"
            class="input"
            maxlength="120"
            :value="altOf(item.mediaId)"
            :disabled="!canEdit"
            :data-testid="`catalog-media-alt-${item.mediaId}`"
            @input="setAlt(item.mediaId, ($event.target as HTMLInputElement).value)"
          />
          <span v-else>{{ altOf(item.mediaId) || "未在页面中使用" }}</span>
        </div>
      </article>
    </div>
  </div>
</template>
