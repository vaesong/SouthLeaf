<script setup lang="ts">
import { computed, inject, onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { authStoreKey } from "../auth/store";
import {
  mediaContentPath,
  newIdempotencyKey,
  type CommandView,
  type MediaView,
  type PreviewView,
  type WorkView,
} from "../content/api";
import { requireContentApi } from "../content/inject";
import { describeFailure } from "../content/messages";

type Operation = "publish" | "withdraw";

const api = requireContentApi();
const auth = inject(authStoreKey);
const route = useRoute();
const workId = String(route.params.workId);
const work = ref<WorkView | null>(null);
const preview = ref<PreviewView | null>(null);
const library = ref<MediaView[]>([]);
const busy = ref<"" | "load" | "save" | Operation>("load");
const notice = ref("");
const requestId = ref("");
const lastCommand = ref<CommandView | null>(null);
// Pending publication intent: same key + same revision on retry; cleared on definitive outcome.
const pending = ref<{ operation: Operation; version: number; key: string } | null>(null);

const form = reactive({
  title: "",
  summary: "",
  subjectScopeRef: "",
  businessIds: "",
  selected: [] as string[],
  alt: {} as Record<string, string>,
  cover: "" as string,
});

const permissions = computed(() => new Set(auth?.state.identity?.permissions ?? []));
const canEdit = computed(() => permissions.value.has("CONTENT_EDIT"));
const canPublish = computed(() => permissions.value.has("CONTENT_PUBLISH"));

function fail(error: unknown): boolean {
  const failure = describeFailure(error);
  notice.value = failure.text;
  requestId.value = failure.requestId;
  return failure.unknown;
}

function fill(view: WorkView) {
  work.value = view;
  form.title = view.title ?? "";
  form.summary = view.summary ?? "";
  form.subjectScopeRef = view.subjectScopeRef ?? "";
  form.businessIds = view.businessIds.join(", ");
  form.selected = view.media.map((m) => m.mediaId);
  form.alt = Object.fromEntries(view.media.map((m) => [m.mediaId, m.altText]));
  form.cover = view.coverMediaId ?? "";
}

async function load() {
  busy.value = "load";
  try {
    const [view, media] = await Promise.all([api.getWork(workId), api.listMedia()]);
    fill(view);
    library.value = media;
    preview.value = await api.preview(workId);
  } catch (error) {
    fail(error);
  } finally {
    busy.value = "";
  }
}

function toggle(mediaId: string) {
  const index = form.selected.indexOf(mediaId);
  if (index >= 0) {
    form.selected.splice(index, 1);
    if (form.cover === mediaId) form.cover = "";
  } else if (form.selected.length < 9) {
    form.selected.push(mediaId);
    form.alt[mediaId] ??= "";
  }
}

async function save() {
  if (!work.value) return;
  busy.value = "save";
  notice.value = "";
  try {
    const saved = await api.saveDraft(workId, work.value.version, {
      title: form.title,
      summary: form.summary,
      subjectScopeRef: form.subjectScopeRef,
      businessIds: form.businessIds
        .split(/[,\s]+/)
        .map((id) => id.trim())
        .filter((id) => id.length > 0),
      media: form.selected.map((mediaId) => ({ mediaId, altText: form.alt[mediaId] ?? "" })),
      coverMediaId: form.cover || null,
    });
    fill(saved);
    preview.value = await api.preview(workId);
    notice.value = `草稿已保存为 v${saved.version}。`;
  } catch (error) {
    fail(error);
  } finally {
    busy.value = "";
  }
}

async function run(operation: Operation) {
  if (!work.value) return;
  const version = work.value.version;
  if (!pending.value || pending.value.operation !== operation || pending.value.version !== version)
    pending.value = { operation, version, key: newIdempotencyKey() };
  busy.value = operation;
  notice.value = "";
  try {
    const call = operation === "publish" ? api.publish : api.withdraw;
    lastCommand.value = await call.call(api, workId, version, pending.value.key);
    pending.value = null;
    notice.value =
      (operation === "publish" ? `已发布 v${lastCommand.value.targetVersion}` : "已撤下") +
      (lastCommand.value.replayed ? "（已确认先前提交的结果）" : "") +
      "。";
    fill(await api.getWork(workId));
    preview.value = await api.preview(workId);
  } catch (error) {
    if (!fail(error)) pending.value = null;
  } finally {
    busy.value = "";
  }
}

onMounted(load);
</script>

<template>
  <main class="identity-shell content-shell">
    <header class="topbar">
      <router-link class="wordmark" to="/works" aria-label="返回作品列表">
        <span class="wordmark-mark">S</span>
        <span>SouthLeaf <small>ADMIN</small></span>
      </router-link>
      <div class="topbar-status">
        <span class="environment-badge">S2-B · 本地验证（非生产）</span>
      </div>
    </header>

    <section v-if="work" class="identity-card content-card" aria-labelledby="work-title">
      <div class="section-title-row">
        <h1 id="work-title">編輯作品</h1>
        <span data-testid="work-version"
          >草稿 v{{ work.version }} · {{ work.publicationState }}</span
        >
      </div>
      <form class="content-form" @submit.prevent="save">
        <label
          >标题（≤ 80）<el-input
            v-model="form.title"
            maxlength="80"
            data-testid="work-title-input"
            :disabled="!canEdit"
        /></label>
        <label
          >摘要（≤ 400）<el-input
            v-model="form.summary"
            type="textarea"
            maxlength="400"
            data-testid="work-summary-input"
            :disabled="!canEdit"
        /></label>
        <label
          >授权主体范围引用<el-input
            v-model="form.subjectScopeRef"
            data-testid="work-scope-input"
            :disabled="!canEdit"
        /></label>
        <label
          >关联业务 ID（≤ 3，逗號分隔）<el-input
            v-model="form.businessIds"
            data-testid="work-business-input"
            :disabled="!canEdit"
        /></label>

        <fieldset>
          <legend>圖片（≤ 9，需選擇封面）</legend>
          <ul class="media-grid">
            <li v-for="item in library" :key="item.mediaId" :data-media-id="item.mediaId">
              <img
                :src="mediaContentPath(item.mediaId, 'thumbnail')"
                :alt="form.alt[item.mediaId] || item.mediaId"
              />
              <label
                ><input
                  type="checkbox"
                  :checked="form.selected.includes(item.mediaId)"
                  :disabled="!canEdit"
                  data-testid="media-select"
                  @change="toggle(item.mediaId)"
                />
                選用</label
              >
              <template v-if="form.selected.includes(item.mediaId)">
                <label
                  ><input
                    v-model="form.cover"
                    type="radio"
                    name="cover"
                    :value="item.mediaId"
                    :disabled="!canEdit"
                    data-testid="media-cover"
                  />
                  封面</label
                >
                <input
                  v-model="form.alt[item.mediaId]"
                  maxlength="120"
                  placeholder="替代文字"
                  :disabled="!canEdit"
                  data-testid="media-alt"
                />
              </template>
            </li>
          </ul>
        </fieldset>

        <div class="identity-actions">
          <el-button
            v-if="canEdit"
            type="primary"
            native-type="submit"
            data-testid="save-draft"
            :loading="busy === 'save'"
            :disabled="busy !== ''"
            >保存草稿</el-button
          >
        </div>
      </form>
    </section>

    <section
      v-if="preview"
      class="identity-card content-card"
      aria-labelledby="preview-title"
      data-testid="preview"
    >
      <h2 id="preview-title">发布预检（服务器判定）</h2>
      <dl class="identity-details">
        <div>
          <dt>可发布</dt>
          <dd data-testid="publishable">{{ preview.publishable ? "是" : "否" }}</dd>
        </div>
        <div>
          <dt>阻断原因</dt>
          <dd data-testid="blocking-reason">{{ preview.blockingReason ?? "—" }}</dd>
        </div>
        <div>
          <dt>授权状态</dt>
          <dd>{{ preview.authorizationState ?? "—" }}</dd>
        </div>
        <div>
          <dt>接受的來源</dt>
          <dd>{{ preview.acceptedProvenance }}</dd>
        </div>
      </dl>
      <p class="helper-text">预检仅供提示；发布时服务器会在同一事务内重新校验全部门禁。</p>
      <div class="identity-actions">
        <el-button
          v-if="canPublish"
          type="success"
          data-testid="publish"
          :loading="busy === 'publish'"
          :disabled="busy !== '' || !preview.publishable"
          @click="run('publish')"
          >发布当前修订</el-button
        >
        <el-button
          v-if="canPublish && work?.publicationState === 'PUBLISHED'"
          type="danger"
          plain
          data-testid="withdraw"
          :loading="busy === 'withdraw'"
          :disabled="busy !== ''"
          @click="run('withdraw')"
          >撤下</el-button
        >
      </div>
      <div
        v-if="pending && busy === ''"
        class="security-status"
        role="status"
        data-testid="pending-intent"
      >
        <span
          >上一次{{
            pending.operation === "publish" ? "发布" : "撤下"
          }}结果未知；重试将沿用同一操作键与修订。</span
        >
      </div>
      <p v-if="lastCommand" class="helper-text" data-testid="last-command">
        命令 {{ lastCommand.commandId }} · {{ lastCommand.operation }} · {{ lastCommand.status }}
      </p>
    </section>

    <p v-if="busy === 'load'" role="status">加载中…</p>
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
