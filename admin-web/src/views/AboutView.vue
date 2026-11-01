<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import AppIcon from "../components/AppIcon.vue";
import AppSwitch from "../components/AppSwitch.vue";
import CatalogSaveButtons from "../components/CatalogSaveButtons.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import MediaField from "../components/MediaField.vue";
import MediaPicker from "../components/MediaPicker.vue";
import MediaThumb from "../components/MediaThumb.vue";
import SideDrawer from "../components/SideDrawer.vue";
import TagListEditor from "../components/TagListEditor.vue";
import MiniAboutPreview from "../components/MiniAboutPreview.vue";
import PhonePreview from "../components/PhonePreview.vue";
import { mediaContentPath, newIdempotencyKey, type MediaView } from "../content/api";
import { CHANNEL_PLATFORMS, QR_STATUSES, moveItem } from "../content/catalogDraft";
import { countLabel } from "../content/format";
import { requireContentApi } from "../content/inject";
import { describeFailure } from "../content/messages";
import { useCatalogAccess, useCatalogLeaveGuard } from "../content/useCatalog";

const api = requireContentApi();
const { store, canEdit } = useCatalogAccess();
const leave = useCatalogLeaveGuard(store);
const leaveOpen = leave.open;
const media = ref<MediaView[]>([]);
const mediaError = ref("");
const uploading = ref(false);
const editingIndex = ref<number | null>(null);
const removeMemberOpen = ref(false);
let uploadKey: string | null = null;

const draft = computed(() => store.state.draft);
const editingMember = computed(() =>
  editingIndex.value === null ? null : (draft.value?.team[editingIndex.value] ?? null),
);
const logoOpen = ref(false);
const qrOpen = ref<number | null>(null);

function platformMark(platform: string) {
  if (platform === "wechat") return { text: "微", color: "#62735A" };
  if (platform === "douyin") return { text: "抖", color: "#2A251F" };
  if (platform === "xiaohongshu") return { text: "红", color: "#A65A34" };
  return { text: "其", color: "#958B7F" };
}

function chooseLogo(id: string) {
  if (!draft.value) return;
  draft.value.brand.logoMediaId = id;
  logoOpen.value = false;
}

function chooseQr(id: string) {
  if (!draft.value || qrOpen.value === null) return;
  const channel = draft.value.channels[qrOpen.value];
  if (channel) channel.qrMediaId = id;
  qrOpen.value = null;
}
const publishedTeam = computed(() =>
  (draft.value?.team ?? [])
    .filter((item) => item.published)
    .map((item) => ({
      name: item.name,
      role: item.role,
      imageUrl: item.portraitMediaId ? mediaContentPath(item.portraitMediaId, "thumbnail") : "",
    })),
);

async function refreshMedia() {
  mediaError.value = "";
  try {
    media.value = await api.listMedia();
  } catch (error) {
    media.value = [];
    mediaError.value = describeFailure(error).text;
  }
}

function addMember() {
  const value = draft.value;
  if (!value) return;
  value.team.push({
    id: `member-${crypto.randomUUID().slice(0, 8)}`,
    name: "",
    role: "",
    bio: "",
    specialties: [],
    portraitMediaId: null,
    position: value.team.length,
    published: false,
  });
  editingIndex.value = value.team.length - 1;
}

async function uploadMedia(file: File) {
  uploading.value = true;
  uploadKey ??= newIdempotencyKey();
  try {
    const result = await api.upload(file, file.name, uploadKey);
    uploadKey = null;
    media.value = [
      result.media,
      ...media.value.filter((item) => item.mediaId !== result.media.mediaId),
    ];
  } catch (error) {
    mediaError.value = describeFailure(error).text;
    if (!describeFailure(error).unknown) uploadKey = null;
  } finally {
    uploading.value = false;
  }
}

function moveEditing(offset: number) {
  const value = draft.value;
  if (!value || editingIndex.value === null) return;
  const next = editingIndex.value + offset;
  if (next < 0 || next >= value.team.length) return;
  moveItem(value.team, editingIndex.value, offset);
  editingIndex.value = next;
}

function confirmRemoveMember() {
  const value = draft.value;
  if (!value || editingIndex.value === null) return;
  value.team.splice(editingIndex.value, 1);
  editingIndex.value = null;
  removeMemberOpen.value = false;
}

function addChannel() {
  const value = draft.value;
  if (!value) return;
  value.channels.push({
    id: `channel-${crypto.randomUUID().slice(0, 8)}`,
    platform: "wechat",
    label: "",
    account: "",
    qrMediaId: null,
    qrStatus: "UNCONFIGURED",
    position: value.channels.length,
    published: false,
  });
}

onMounted(async () => {
  await store.load();
  await refreshMedia();
});
</script>

<template>
  <div v-if="draft" class="edit-layout">
    <div class="stack">
      <div class="page-head">
        <div>
          <h1>关于我们</h1>
          <p>工作室介绍、摄影师、地址电话和社交账号，都会出现在小程序关于页。</p>
        </div>
        <div class="actions">
          <CatalogSaveButtons :disabled="!canEdit" />
        </div>
      </div>
      <div class="card">
        <div class="card-head"><h2>品牌信息</h2></div>
        <div class="card-pad stack">
          <div class="logo-edit">
            <button
              class="logo-big"
              type="button"
              data-testid="catalog-brand-logo-media"
              :disabled="!canEdit"
              @click="logoOpen = true"
            >
              <img
                v-if="draft.brand.logoMediaId"
                :src="mediaContentPath(draft.brand.logoMediaId, 'thumbnail')"
                alt=""
                :data-testid="`catalog-media-preview-${draft.brand.logoMediaId}`"
              />
              <span v-else>{{ draft.brand.name.trim().slice(0, 1) || "枝" }}</span>
            </button>
            <div class="field">
              <label>品牌标志</label>
              <button
                class="btn btn-secondary btn-sm"
                type="button"
                :disabled="!canEdit"
                @click="logoOpen = true"
              >
                {{ draft.brand.logoMediaId ? "更换" : "从图片库选择" }}
              </button>
            </div>
          </div>
          <MediaPicker
            :open="logoOpen"
            :media="media"
            :model-value="draft.brand.logoMediaId"
            :uploading="uploading"
            @close="logoOpen = false"
            @choose="chooseLogo"
            @upload="uploadMedia"
          />
          <div class="grid-2">
            <div class="field">
              <label
                >工作室名称<em>{{ countLabel(draft.brand.name.length, 255) }}</em></label
              >
              <input
                v-model="draft.brand.name"
                class="input"
                maxlength="255"
                data-testid="catalog-brand-name"
                :disabled="!canEdit"
              />
            </div>
            <div class="field">
              <label
                >英文名<em>{{ countLabel(draft.brand.englishName.length, 255) }}</em></label
              >
              <input
                v-model="draft.brand.englishName"
                class="input"
                maxlength="255"
                data-testid="catalog-brand-english-name"
                :disabled="!canEdit"
              />
            </div>
            <div class="field span-2">
              <label
                >一句话介绍<em>{{ countLabel(draft.brand.tagline.length, 1024) }}</em></label
              >
              <input
                v-model="draft.brand.tagline"
                class="input"
                maxlength="1024"
                data-testid="catalog-brand-tagline"
                :disabled="!canEdit"
              />
            </div>
            <div class="field span-2">
              <label
                >品牌故事<em>{{ countLabel(draft.brand.description.length, 16000) }}</em></label
              >
              <textarea
                v-model="draft.brand.description"
                class="textarea"
                maxlength="16000"
                data-testid="catalog-brand-description"
                :disabled="!canEdit"
              ></textarea>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-head"><h2>联系方式</h2></div>
        <div class="card-pad grid-2">
          <div class="field">
            <label
              >联系电话<em>{{ countLabel(draft.contact.phone.length, 64) }}</em></label
            >
            <input
              v-model="draft.contact.phone"
              class="input"
              maxlength="64"
              data-testid="catalog-contact-phone"
              :disabled="!canEdit"
            />
          </div>
          <div class="field">
            <label
              >营业时间<em>{{ countLabel(draft.contact.workingHours.length, 1024) }}</em></label
            >
            <input
              v-model="draft.contact.workingHours"
              class="input"
              maxlength="1024"
              data-testid="catalog-contact-hours"
              :disabled="!canEdit"
            />
          </div>
          <div class="field span-2">
            <label
              >工作室地址<em>{{ countLabel(draft.contact.address.length, 1024) }}</em></label
            >
            <input
              v-model="draft.contact.address"
              class="input"
              maxlength="1024"
              data-testid="catalog-contact-address"
              :disabled="!canEdit"
            />
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <h2>摄影师与团队</h2>
          <div class="right">
            <button
              class="btn btn-secondary btn-sm"
              type="button"
              data-testid="catalog-add-team"
              :disabled="!canEdit"
              @click="addMember"
            >
              <AppIcon name="plus" />添加成员
            </button>
          </div>
        </div>
        <div class="team-grid">
          <article
            v-for="(member, index) in draft.team"
            :key="member.id"
            class="member"
            data-testid="catalog-team-item"
          >
            <MediaThumb :media-id="member.portraitMediaId" />
            <div>
              <div class="member-top">
                <div>
                  <b>{{ member.name || "未命名" }}</b>
                  <small>{{ member.role || "未填写职位" }}</small>
                </div>
                <div class="tools">
                  <button
                    class="icon-btn"
                    type="button"
                    data-testid="catalog-team-edit"
                    aria-label="编辑"
                    @click="editingIndex = index"
                  >
                    <svg viewBox="0 0 24 24"><path d="M4 20h4L19 9l-4-4L4 16z" /></svg>
                  </button>
                  <button
                    class="icon-btn"
                    type="button"
                    :aria-label="member.published ? '隐藏' : '显示'"
                    :disabled="!canEdit"
                    @click="member.published = !member.published"
                  >
                    <svg viewBox="0 0 24 24">
                      <path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z" />
                    </svg>
                  </button>
                </div>
              </div>
              <p>{{ member.bio || "还没有介绍" }}</p>
              <div class="tags">
                <span v-for="tag in member.specialties" :key="tag" class="chip chip-accent">{{
                  tag
                }}</span>
                <span class="chip" :class="member.published ? 'chip-live' : 'chip-off'">
                  <i></i>{{ member.published ? "展示中" : "已隐藏" }}
                </span>
              </div>
            </div>
          </article>
          <button class="member add" type="button" :disabled="!canEdit" @click="addMember">
            添加一位成员
          </button>
        </div>
      </div>
      <SideDrawer :open="editingMember !== null" title="编辑成员" @close="editingIndex = null">
        <template v-if="editingMember">
          <MediaField
            v-model="editingMember.portraitMediaId"
            :media="media"
            test-id="catalog-team-portrait-media"
            :disabled="!canEdit"
            :uploading="uploading"
            @upload="uploadMedia"
          />
          <div class="grid-2">
            <div class="field">
              <label>姓名</label>
              <input
                v-model="editingMember.name"
                class="input"
                maxlength="255"
                data-testid="catalog-team-name"
                :disabled="!canEdit"
              />
            </div>
            <div class="field">
              <label>职位</label>
              <input
                v-model="editingMember.role"
                class="input"
                maxlength="255"
                data-testid="catalog-team-role"
                :disabled="!canEdit"
              />
            </div>
            <div class="field span-2">
              <label>介绍</label>
              <textarea
                v-model="editingMember.bio"
                class="textarea"
                maxlength="12000"
                data-testid="catalog-team-bio"
                :disabled="!canEdit"
              ></textarea>
            </div>
            <div class="field span-2">
              <label>擅长方向</label>
              <TagListEditor
                v-model="editingMember.specialties"
                test-id="catalog-team-specialties"
                :disabled="!canEdit"
                :max="20"
              />
            </div>
          </div>
          <div class="switch-row">
            在小程序展示
            <AppSwitch
              v-model="editingMember.published"
              label="在小程序展示"
              test-id="catalog-team-published"
              :disabled="!canEdit"
            />
          </div>
        </template>
        <template #footer>
          <button
            class="btn btn-ghost btn-sm"
            type="button"
            data-testid="catalog-team-up"
            :disabled="!canEdit || editingIndex === 0"
            @click="moveEditing(-1)"
          >
            上移
          </button>
          <button
            class="btn btn-ghost btn-sm"
            type="button"
            data-testid="catalog-team-down"
            :disabled="!canEdit || editingIndex === null || editingIndex >= draft.team.length - 1"
            @click="moveEditing(1)"
          >
            下移
          </button>
          <button
            class="btn btn-danger-ghost btn-sm"
            type="button"
            data-testid="catalog-team-remove"
            :disabled="!canEdit"
            @click="removeMemberOpen = true"
          >
            删除
          </button>
          <div class="right">
            <button class="btn btn-primary btn-sm" type="button" @click="editingIndex = null">
              完成
            </button>
          </div>
        </template>
      </SideDrawer>

      <div class="card">
        <div class="card-head">
          <h2>社交账号</h2>
          <div class="right">
            <button
              class="btn btn-secondary btn-sm"
              type="button"
              data-testid="catalog-add-channel"
              :disabled="!canEdit"
              @click="addChannel"
            >
              <AppIcon name="plus" />添加账号
            </button>
          </div>
        </div>
        <div class="channels">
          <article
            v-for="(channel, index) in draft.channels"
            :key="channel.id"
            class="channel"
            data-testid="catalog-channel-item"
          >
            <div>
              <div
                class="ch-ico"
                :style="{ background: platformMark(channel.platform).color, color: '#fff' }"
              >
                {{ platformMark(channel.platform).text }}
              </div>
              <select
                v-model="channel.platform"
                class="select"
                data-testid="catalog-channel-platform"
                :disabled="!canEdit"
              >
                <option v-for="item in CHANNEL_PLATFORMS" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
            </div>
            <input
              v-model="channel.label"
              class="input"
              maxlength="255"
              placeholder="显示名称"
              data-testid="catalog-channel-label"
              :disabled="!canEdit"
            />
            <input
              v-model="channel.account"
              class="input"
              maxlength="255"
              placeholder="账号"
              data-testid="catalog-channel-account"
              :disabled="!canEdit"
            />
            <div>
              <img
                v-if="channel.qrMediaId"
                class="qr"
                :src="mediaContentPath(channel.qrMediaId, 'thumbnail')"
                alt=""
                :data-testid="`catalog-media-preview-${channel.qrMediaId}`"
              />
              <div v-else class="qr empty">未上传</div>
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                data-testid="catalog-channel-qr-media"
                :disabled="!canEdit"
                @click="qrOpen = index"
              >
                更换
              </button>
              <select
                v-model="channel.qrStatus"
                class="select"
                data-testid="catalog-channel-qr-status"
                :disabled="!canEdit"
              >
                <option v-for="item in QR_STATUSES" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
            </div>
            <div class="slide-tools">
              <AppSwitch
                v-model="channel.published"
                label="展示社交账号"
                test-id="catalog-channel-published"
                :disabled="!canEdit"
              />
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                data-testid="catalog-channel-up"
                :disabled="!canEdit || index === 0"
                @click="moveItem(draft.channels, index, -1)"
              >
                上移
              </button>
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                data-testid="catalog-channel-down"
                :disabled="!canEdit || index === draft.channels.length - 1"
                @click="moveItem(draft.channels, index, 1)"
              >
                下移
              </button>
              <button
                class="btn btn-danger-ghost btn-sm"
                type="button"
                :disabled="!canEdit"
                @click="draft.channels.splice(index, 1)"
              >
                移除
              </button>
            </div>
          </article>
          <MediaPicker
            :open="qrOpen !== null"
            :media="media"
            :model-value="qrOpen === null ? null : (draft.channels[qrOpen]?.qrMediaId ?? null)"
            :uploading="uploading"
            @close="qrOpen = null"
            @choose="chooseQr"
            @upload="uploadMedia"
          />
        </div>
      </div>

      <button
        class="btn btn-ghost btn-sm"
        type="button"
        data-testid="catalog-refresh-media"
        @click="refreshMedia"
      >
        刷新图片
      </button>
      <p v-if="mediaError" class="alert-line" role="alert">{{ mediaError }}</p>
    </div>
    <PhonePreview>
      <template #caption>小程序“关于”页预览（草稿）</template>
      <MiniAboutPreview
        :name="draft.brand.name"
        :english-name="draft.brand.englishName"
        :tagline="draft.brand.tagline"
        :description="draft.brand.description"
        :phone="draft.contact.phone"
        :address="draft.contact.address"
        :hours="draft.contact.workingHours"
        :logo-url="
          draft.brand.logoMediaId ? mediaContentPath(draft.brand.logoMediaId, 'thumbnail') : ''
        "
        :team="publishedTeam"
      />
    </PhonePreview>
    <ConfirmDialog
      :open="removeMemberOpen"
      title="删除成员"
      message="删除后，保存草稿才会从目录里去掉这位成员。"
      confirm-text="确认删除"
      danger
      @cancel="removeMemberOpen = false"
      @confirm="confirmRemoveMember"
    />
    <ConfirmDialog
      :open="leaveOpen"
      title="有未保存的修改"
      message="还有没保存的修改。确定离开这一页？"
      confirm-text="离开"
      @cancel="leave.stay"
      @confirm="leave.allow"
    />
  </div>
  <p v-else-if="store.state.status === 'loading'">正在读取草稿…</p>
</template>
