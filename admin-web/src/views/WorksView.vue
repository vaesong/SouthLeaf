<script setup lang="ts">
import { computed, inject, onMounted, ref, shallowRef } from "vue";
import { useRouter } from "vue-router";
import { authStoreKey } from "../auth/store";
import { mediaContentPath, newIdempotencyKey, type MediaView, type WorkView } from "../content/api";
import { requireContentApi } from "../content/inject";
import { describeFailure } from "../content/messages";

const api = requireContentApi();
const auth = inject(authStoreKey);
const router = useRouter();
const works = ref<WorkView[]>([]);
const media = ref<MediaView[]>([]);
const loading = ref(true);
const busy = ref<"" | "create" | "upload">("");
const notice = ref("");
const requestId = ref("");
const fileInput = ref<HTMLInputElement | null>(null);
// One key per user intent; kept only until the intent reaches a definitive outcome.
let createKey: string | null = null;
const uploadIntent = shallowRef<{ key: string; file: File } | null>(null);

const permissions = computed(() => new Set(auth?.state.identity?.permissions ?? []));
const canEdit = computed(() => permissions.value.has("CONTENT_EDIT"));

function fail(error: unknown): boolean {
  const failure = describeFailure(error);
  notice.value = failure.text;
  requestId.value = failure.requestId;
  return failure.unknown;
}

async function load() {
  loading.value = true;
  notice.value = "";
  try {
    [works.value, media.value] = await Promise.all([api.listWorks(), api.listMedia()]);
  } catch (error) {
    fail(error);
  } finally {
    loading.value = false;
  }
}

async function createWork() {
  busy.value = "create";
  notice.value = "";
  createKey ??= newIdempotencyKey();
  try {
    const created = await api.createWork(createKey);
    createKey = null;
    await router.push({ name: "work-edit", params: { workId: created.work.workId } });
  } catch (error) {
    if (!fail(error)) createKey = null;
  } finally {
    busy.value = "";
  }
}

async function upload(file: File) {
  busy.value = "upload";
  notice.value = "";
  if (!uploadIntent.value || uploadIntent.value.file !== file)
    uploadIntent.value = { key: newIdempotencyKey(), file };
  try {
    const result = await api.upload(file, file.name, uploadIntent.value.key);
    uploadIntent.value = null;
    media.value = [result.media, ...media.value.filter((m) => m.mediaId !== result.media.mediaId)];
    notice.value = result.replayed ? "已确认先前的上传结果。" : "圖片已安全處理並保存。";
  } catch (error) {
    if (!fail(error)) uploadIntent.value = null;
  } finally {
    busy.value = "";
    if (fileInput.value) fileInput.value.value = "";
  }
}

function onFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (file) void upload(file);
}

function retryUpload() {
  if (uploadIntent.value) void upload(uploadIntent.value.file);
}

onMounted(load);
</script>

<template>
  <main class="identity-shell content-shell">
    <header class="topbar">
      <router-link class="wordmark" to="/" aria-label="SouthLeaf 管理首页">
        <span class="wordmark-mark">S</span>
        <span>SouthLeaf <small>ADMIN</small></span>
      </router-link>
      <div class="topbar-status">
        <span class="environment-badge">S2-B · 本地验证（非生产）</span>
      </div>
    </header>

    <section class="identity-card content-card" aria-labelledby="works-title">
      <div class="section-title-row">
        <h1 id="works-title">作品</h1>
        <el-button
          v-if="canEdit"
          type="primary"
          data-testid="create-work"
          :loading="busy === 'create'"
          :disabled="busy !== ''"
          @click="createWork"
          >新建作品</el-button
        >
      </div>
      <p v-if="loading" role="status">加载中…</p>
      <table v-else-if="works.length" class="content-table" data-testid="works-table">
        <thead>
          <tr>
            <th>标题</th>
            <th>草稿修訂</th>
            <th>发布状态</th>
            <th>更新时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="work in works" :key="work.workId">
            <td>
              <router-link :to="{ name: 'work-edit', params: { workId: work.workId } }">{{
                work.title || "（未命名）"
              }}</router-link>
            </td>
            <td>v{{ work.version }}</td>
            <td :data-testid="`work-state-${work.workId}`">
              {{ work.publicationState
              }}<span v-if="work.publishedVersion"> · v{{ work.publishedVersion }}</span>
            </td>
            <td>{{ new Date(work.updatedAt).toLocaleString("zh-HK") }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="empty-permissions">尚無作品。</p>
    </section>

    <section class="identity-card content-card" aria-labelledby="media-title">
      <div class="section-title-row">
        <h2 id="media-title">圖片庫</h2>
        <label v-if="canEdit" class="upload-label">
          <input
            ref="fileInput"
            type="file"
            accept="image/jpeg,image/png"
            data-testid="media-file"
            :disabled="busy !== ''"
            @change="onFile"
          />
        </label>
      </div>
      <p class="helper-text">JPEG/PNG，≤ 4 MiB。服务器重新编码并移除元数据，不保留原档。</p>
      <div v-if="uploadIntent && busy === ''" class="security-status" role="status">
        <span>上一次上傳結果未知。</span>
        <el-button text data-testid="retry-upload" @click="retryUpload">以同一操作键重试</el-button>
      </div>
      <ul class="media-grid" data-testid="media-grid">
        <li v-for="item in media" :key="item.mediaId" :data-media-id="item.mediaId">
          <img
            :src="mediaContentPath(item.mediaId, 'thumbnail')"
            :alt="item.mediaId"
            loading="lazy"
          />
          <small>{{ item.sourceFormat }} · {{ item.sourceWidth }}×{{ item.sourceHeight }}</small>
        </li>
      </ul>
    </section>

    <div
      v-if="notice"
      class="form-feedback"
      role="alert"
      aria-live="assertive"
      data-testid="content-notice"
    >
      <span>{{ notice }}</span>
      <small v-if="requestId">Request ID: {{ requestId }}</small>
    </div>
  </main>
</template>
