<script setup lang="ts">
import { computed, inject, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { authStoreKey } from "../auth/store";
import AppIcon from "../components/AppIcon.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import MediaPicker from "../components/MediaPicker.vue";
import SideDrawer from "../components/SideDrawer.vue";
import StatusChip from "../components/StatusChip.vue";
import {
  mediaContentPath,
  newIdempotencyKey,
  type CommandView,
  type MediaView,
  type PreviewView,
  type WorkView,
} from "../content/api";
import {
  rememberUploadedMedia,
  uploadFailureText,
  uploadFilesSequentially,
} from "../content/uploadFiles";
import { addWorkMedia, WORK_MEDIA_LIMIT_TEXT } from "../content/workMedia";
import { catalogStoreKey } from "../content/catalogStore";
import { requireContentApi } from "../content/inject";
import {
  businessNameLabel,
  countLabel,
  publishBlockLabel,
  workStateLabel,
} from "../content/format";
import { describeFailure } from "../content/messages";

type Operation = "publish" | "withdraw";

const SCOPE_REF = /^[A-Za-z0-9._:-]{1,64}$/;
const FIXED_BUSINESSES = ["child", "family", "wedding", "portrait", "maternity", "id-photo"];

const api = requireContentApi();
const auth = inject(authStoreKey);
const catalog = inject(catalogStoreKey);
const route = useRoute();
const router = useRouter();
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
const pickerOpen = ref(false);
const withdrawOpen = ref(false);
const uploading = ref(false);

const businessChoices = computed(() =>
  FIXED_BUSINESSES.map((id) => {
    const named = catalog?.state.draft?.businesses.find((item) => item.id === id)?.name?.trim();
    return { id, label: named || businessNameLabel(id) };
  }),
);

const form = reactive({
  title: "",
  summary: "",
  subjectScopeRef: "",
  businessIds: "",
  selected: [] as string[],
  cover: "" as string,
});

const permissions = computed(() => new Set(auth?.state.identity?.permissions ?? []));
const canEdit = computed(() => permissions.value.has("CONTENT_EDIT"));
const canPublish = computed(() => permissions.value.has("CONTENT_PUBLISH"));
const scopeError = computed(() => {
  const value = form.subjectScopeRef;
  if (!value) return "";
  return SCOPE_REF.test(value) ? "" : "编号只能包含字母、数字和 . _ : -，最长 64 个字符。";
});
const drawerTitle = computed(() => form.title.trim() || work.value?.title?.trim() || "未命名作品");
const statusChip = computed(() => {
  const current = work.value;
  if (!current) return { tone: "draft" as const, text: "草稿" };
  const unpublished =
    current.publicationState === "PUBLISHED" &&
    current.publishedVersion !== null &&
    current.version !== current.publishedVersion;
  if (unpublished) return { tone: "warn" as const, text: "有未发布修改" };
  const text = workStateLabel(current.publicationState);
  if (current.publicationState === "PUBLISHED") return { tone: "live" as const, text };
  if (current.publicationState === "WITHDRAWN") return { tone: "off" as const, text };
  return { tone: "draft" as const, text };
});

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

function businessIds() {
  return form.businessIds
    .split(/[,\s]+/)
    .map((id) => id.trim())
    .filter((id) => id.length > 0);
}

function toggleBusiness(id: string) {
  const current = businessIds();
  const next = current.includes(id)
    ? current.filter((item) => item !== id)
    : current.length >= 3
      ? current
      : [...current, id];
  form.businessIds = next.join(", ");
}

function chooseMedia(mediaId: string) {
  const outcome = addWorkMedia(form.selected, mediaId);
  if (outcome === "added") {
    if (!form.cover) form.cover = mediaId;
  } else if (outcome === "full") {
    notice.value = WORK_MEDIA_LIMIT_TEXT;
  }
  pickerOpen.value = false;
}

async function uploadMedia(files: File[]) {
  uploading.value = true;
  notice.value = "";
  let blocked = false;
  const failure = await uploadFilesSequentially(files, async (file, key) => {
    const result = await api.upload(file, file.name, key);
    library.value = rememberUploadedMedia(library.value, result.media);
    const outcome = addWorkMedia(form.selected, result.media.mediaId);
    if (outcome === "added") {
      if (!form.cover) form.cover = result.media.mediaId;
    } else if (outcome === "full") blocked = true;
  });
  if (failure) {
    notice.value = uploadFailureText(failure.file.name, failure.error);
    requestId.value = describeFailure(failure.error).requestId;
  } else if (blocked) {
    notice.value = WORK_MEDIA_LIMIT_TEXT;
  }
  uploading.value = false;
}

function toggle(mediaId: string) {
  const index = form.selected.indexOf(mediaId);
  if (index >= 0) {
    form.selected.splice(index, 1);
    if (form.cover === mediaId) form.cover = "";
    return;
  }
  addWorkMedia(form.selected, mediaId);
}

async function save() {
  if (!work.value) return;
  if (scopeError.value) {
    notice.value = "肖像授权编号格式不对。";
    return;
  }
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
      media: form.selected.map((mediaId) => ({ mediaId, altText: "" })),
      coverMediaId: form.cover || null,
    });
    fill(saved);
    preview.value = await api.preview(workId);
    notice.value = "草稿已保存。";
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
      (operation === "publish" ? "已发布" : "已撤下") +
      (lastCommand.value.replayed ? "。已确认先前提交的结果" : "") +
      "。";
    fill(await api.getWork(workId));
    preview.value = await api.preview(workId);
  } catch (error) {
    if (!fail(error)) pending.value = null;
  } finally {
    busy.value = "";
  }
}

function close() {
  void router.push({ name: "works" });
}

onMounted(() => {
  void catalog?.load();
  void load();
});
</script>

<template>
  <SideDrawer :open="true" :title="drawerTitle" :width="640" @close="close">
    <template v-if="work" #heading>
      <span>{{ drawerTitle }}</span>
      <StatusChip :tone="statusChip.tone" :text="statusChip.text" data-testid="work-version" />
    </template>
    <div v-if="work" class="stack">
      <form id="work-edit-form" class="stack" @submit.prevent="save">
        <div class="grid-2">
          <div class="field">
            <label
              >标题<em>{{ countLabel(form.title.length, 80) }}</em></label
            >
            <input
              v-model="form.title"
              class="input"
              maxlength="80"
              data-testid="work-title-input"
              :disabled="!canEdit"
            />
          </div>
          <div class="field">
            <label>肖像授权编号</label>
            <input
              v-model="form.subjectScopeRef"
              class="input"
              maxlength="64"
              placeholder="填写授权书上的编号，例如 SQ-2026-001"
              data-testid="work-scope-input"
              :disabled="!canEdit"
            />
            <p class="hint">没有授权编号的作品不能发布</p>
            <p v-if="scopeError" class="hint" style="color: var(--danger)">{{ scopeError }}</p>
          </div>
          <div class="field span-2">
            <label
              >介绍<em>{{ countLabel(form.summary.length, 400) }}</em></label
            >
            <textarea
              v-model="form.summary"
              class="textarea"
              maxlength="400"
              data-testid="work-summary-input"
              :disabled="!canEdit"
            ></textarea>
          </div>
          <div class="field span-2">
            <label>所属业务</label>
            <div class="check-row" data-testid="work-business-input">
              <button
                v-for="biz in businessChoices"
                :key="biz.id"
                class="check"
                :class="{ on: businessIds().includes(biz.id) }"
                type="button"
                :disabled="!canEdit"
                @click="toggleBusiness(biz.id)"
              >
                {{ biz.label }}
              </button>
            </div>
          </div>
        </div>
        <div class="field">
          <label>图片<em>最多 9 张 · 点星标设为封面</em></label>
          <div class="thumbs">
            <div v-for="mediaId in form.selected" :key="mediaId" class="work-thumb">
              <div class="thumb" :class="{ cover: form.cover === mediaId }">
                <div class="ph">
                  <img :src="mediaContentPath(mediaId, 'thumbnail')" alt="" />
                </div>
                <span v-if="form.cover === mediaId" class="cover-badge">封面</span>
                <div class="over">
                  <button
                    class="icon-btn"
                    type="button"
                    aria-label="设为封面"
                    data-testid="media-cover"
                    :disabled="!canEdit"
                    @click="form.cover = mediaId"
                  >
                    <AppIcon name="star" />
                  </button>
                  <button
                    class="icon-btn danger"
                    type="button"
                    aria-label="移除"
                    data-testid="media-select"
                    :disabled="!canEdit"
                    @click="toggle(mediaId)"
                  >
                    <AppIcon name="close" />
                  </button>
                </div>
              </div>
            </div>
            <button
              v-if="form.selected.length < 9"
              class="upload"
              type="button"
              :disabled="!canEdit"
              @click="pickerOpen = true"
            >
              <AppIcon name="plus" />
              添加
            </button>
          </div>
        </div>
      </form>
      <p v-if="preview?.publishable" class="chip chip-live" data-testid="publishable">可以发布</p>
      <div v-else-if="preview" class="note" data-testid="blocking-reason">
        <b>说明</b>
        <span>{{ publishBlockLabel(preview.blockingReason) }}</span>
      </div>
      <p v-if="pending && busy === ''" data-testid="pending-intent">
        上一次{{ pending.operation === "publish" ? "发布" : "撤下" }}还没确认。再点一次会接着完成。
      </p>
      <p v-if="lastCommand" data-testid="last-command">
        {{ lastCommand.operation === "PUBLISH" ? "发布已完成" : "撤下已完成" }}
      </p>
      <p v-if="notice" role="alert" data-testid="content-notice">{{ notice }}</p>
      <MediaPicker
        :open="pickerOpen"
        :media="library"
        :uploading="uploading"
        :notice="notice"
        @close="pickerOpen = false"
        @choose="chooseMedia"
        @upload="uploadMedia"
      />
    </div>
    <p v-else-if="busy === 'load'">正在读取作品…</p>
    <p v-else-if="notice" role="alert" data-testid="content-notice">{{ notice }}</p>
    <template v-if="work" #footer>
      <button
        v-if="canPublish && work.publicationState === 'PUBLISHED'"
        class="btn btn-danger-ghost"
        type="button"
        data-testid="withdraw"
        :disabled="busy !== ''"
        @click="withdrawOpen = true"
      >
        从小程序撤下
      </button>
      <div class="right">
        <button
          v-if="canEdit"
          class="btn btn-secondary"
          type="submit"
          form="work-edit-form"
          data-testid="save-draft"
          :disabled="busy !== ''"
        >
          保存草稿
        </button>
        <button
          v-if="canPublish"
          class="btn btn-accent"
          type="button"
          data-testid="publish"
          :disabled="busy !== '' || !preview?.publishable"
          @click="run('publish')"
        >
          发布这组作品
        </button>
      </div>
    </template>
    <ConfirmDialog
      :open="withdrawOpen"
      title="从小程序撤下"
      message="撤下后，客人在小程序里就看不到这组作品了。"
      confirm-text="确认撤下"
      danger
      @cancel="withdrawOpen = false"
      @confirm="
        withdrawOpen = false;
        run('withdraw');
      "
    />
  </SideDrawer>
</template>
