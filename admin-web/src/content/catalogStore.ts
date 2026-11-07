import { computed, reactive, type InjectionKey } from "vue";
import { AdminApiError } from "../auth/api";
import {
  newIdempotencyKey,
  type AdminContentApi,
  type CatalogDraftInput,
  type CatalogPreview,
  type CatalogView,
} from "./api";
import {
  ensurePresentation,
  normalizeDraft,
  snapshotOf,
  syncMediaAltTexts,
  unpublishedHint,
} from "./catalogDraft";
import { shopReason } from "./format";
import { describeFailure } from "./messages";

interface CatalogState {
  status: "idle" | "loading" | "ready" | "error";
  revision: number | null;
  draft: CatalogDraftInput | null;
  savedSnapshot: string;
  notice: string;
  requestId: string;
  conflict: boolean;
  savedUnpublished: boolean;
  busy: "" | "save" | "preview" | "publish" | "withdraw" | "resolve" | "history";
  unknownAction: "" | "publish" | "withdraw";
  preview: CatalogPreview | null;
}

export interface CatalogStore {
  readonly state: CatalogState;
  readonly dirty: boolean;
  readonly unpublishedLabel: string;
  load(force?: boolean): Promise<void>;
  save(): Promise<boolean>;
  discard(): void;
  previewDraft(): Promise<void>;
  publish(): Promise<void>;
  withdraw(): Promise<void>;
  resolveUnknown(): Promise<void>;
  applyHistoricDraft(view: CatalogView, serverRevision: number): void;
  clearNotice(): void;
}

function conflictKind(error: unknown): "version" | "stale" | "idempotent" | null {
  if (!(error instanceof AdminApiError)) return null;
  if (error.code === "IDEMPOTENCY_KEY_REUSE") return "idempotent";
  if (error.code === "PRECONDITION_REQUIRED" || error.status === 428) return "stale";
  if (error.code === "NOT_PUBLISHED") return null;
  if (error.code === "VERSION_CONFLICT" || error.status === 409 || error.status === 412)
    return "version";
  return null;
}

export function createCatalogStore(api: AdminContentApi): CatalogStore {
  const state = reactive<CatalogState>({
    status: "idle",
    revision: null,
    draft: null,
    savedSnapshot: "",
    notice: "",
    requestId: "",
    conflict: false,
    savedUnpublished: false,
    busy: "",
    unknownAction: "",
    preview: null,
  });
  let publishKey: string | null = null;
  let withdrawKey: string | null = null;
  let inflight: Promise<void> | null = null;

  const dirty = computed(
    () => state.draft !== null && snapshotOf(state.draft) !== state.savedSnapshot,
  );
  const unpublishedLabel = computed(() => unpublishedHint(dirty.value, state.savedUnpublished));

  function fail(error: unknown): boolean {
    const result = describeFailure(error);
    state.notice = result.text;
    state.requestId = result.requestId;
    return result.unknown;
  }

  function noteConflict(error: unknown): boolean {
    const kind = conflictKind(error);
    if (kind !== "version" && kind !== "stale") return false;
    state.conflict = true;
    if (kind === "version") {
      state.notice = "内容已在别处更新";
      state.requestId = error instanceof AdminApiError ? (error.requestId ?? "") : "";
    } else {
      fail(error);
    }
    return true;
  }

  function noteWriteConflict(
    error: unknown,
    action: "publish" | "withdraw",
    dropKey: () => void,
  ): boolean {
    const kind = conflictKind(error);
    if (kind === "idempotent") {
      state.unknownAction = action;
      state.notice = "这次提交和上次不是同一次操作。请先确认上次是否成功。";
      state.requestId = error instanceof AdminApiError ? (error.requestId ?? "") : "";
      return true;
    }
    if (kind === "version" || kind === "stale") {
      dropKey();
      state.unknownAction = "";
      noteConflict(error);
      return true;
    }
    return false;
  }

  function accept(view: CatalogView, savedUnpublished = state.savedUnpublished) {
    const draft = structuredClone(view.draft);
    if (!Array.isArray(draft.heroSlides)) draft.heroSlides = [];
    ensurePresentation(draft);
    syncMediaAltTexts(draft);
    state.revision = view.revision;
    state.draft = draft;
    state.savedSnapshot = snapshotOf(draft);
    state.savedUnpublished = savedUnpublished;
    state.preview = null;
    state.conflict = false;
    state.status = "ready";
  }

  async function load(force = false): Promise<void> {
    if (!force && state.draft && state.status === "ready") return;
    if (inflight && !force) return inflight;
    state.status = state.draft ? state.status : "loading";
    const run = (async () => {
      try {
        accept(await api.getCatalog(), force ? false : state.savedUnpublished);
        state.notice = "";
        state.requestId = "";
      } catch (error) {
        state.status = state.draft ? "ready" : "error";
        fail(error);
      }
    })();
    inflight = run;
    try {
      await run;
    } finally {
      if (inflight === run) inflight = null;
    }
  }

  async function save(): Promise<boolean> {
    if (!state.draft || state.revision === null) return false;
    normalizeDraft(state.draft);
    state.busy = "save";
    state.notice = "";
    state.requestId = "";
    try {
      accept(await api.saveCatalog(state.revision, state.draft), true);
      state.notice = "已保存。小程序上看到的还是上一版。";
      return true;
    } catch (error) {
      if (!noteConflict(error)) fail(error);
      return false;
    } finally {
      state.busy = "";
    }
  }

  function discard() {
    if (!state.savedSnapshot) return;
    state.draft = JSON.parse(state.savedSnapshot) as CatalogDraftInput;
    state.preview = null;
    state.notice = "已放弃尚未保存的修改。";
  }

  async function previewDraft() {
    state.busy = "preview";
    state.notice = "";
    try {
      state.preview = await api.previewCatalog();
      state.notice = state.preview.publishable
        ? "可以发布到小程序。"
        : shopReason(state.preview.blockingReason);
    } catch (error) {
      state.preview = null;
      fail(error);
    } finally {
      state.busy = "";
    }
  }

  async function publish() {
    if (state.revision === null) return;
    state.busy = "publish";
    state.notice = "";
    try {
      const preview = await api.previewCatalog();
      state.preview = preview;
      if (!preview.publishable) {
        state.notice = shopReason(preview.blockingReason);
        return;
      }
      publishKey ??= newIdempotencyKey();
      await api.publishCatalog(state.revision, publishKey);
      publishKey = null;
      state.unknownAction = "";
      state.savedUnpublished = false;
      state.notice = "已发布到小程序。";
    } catch (error) {
      const held = noteWriteConflict(error, "publish", () => {
        publishKey = null;
      });
      if (held) return;
      const unknown = fail(error);
      state.unknownAction = unknown ? "publish" : "";
      if (!unknown) publishKey = null;
    } finally {
      state.busy = "";
    }
  }

  async function withdraw() {
    if (state.revision === null) return;
    state.busy = "withdraw";
    state.notice = "";
    try {
      withdrawKey ??= newIdempotencyKey();
      await api.withdrawCatalog(state.revision, withdrawKey);
      withdrawKey = null;
      state.unknownAction = "";
      state.notice = "小程序上的内容已下架。";
    } catch (error) {
      const held = noteWriteConflict(error, "withdraw", () => {
        withdrawKey = null;
      });
      if (held) return;
      const unknown = fail(error);
      state.unknownAction = unknown ? "withdraw" : "";
      if (!unknown) withdrawKey = null;
    } finally {
      state.busy = "";
    }
  }

  async function resolveUnknown() {
    const key = state.unknownAction === "publish" ? publishKey : withdrawKey;
    if (!key) return;
    state.busy = "resolve";
    try {
      const result = await api.findCatalogCommand(key);
      if (state.unknownAction === "publish") publishKey = null;
      else withdrawKey = null;
      state.unknownAction = "";
      state.notice = "刚才那次操作已经完成。";
    } catch (error) {
      if (error instanceof AdminApiError && error.status === 404) {
        state.notice = "还没查到结果。可以再试一次，不会重复发布。";
        state.requestId = error.requestId ?? "";
      } else fail(error);
    } finally {
      state.busy = "";
    }
  }

  function applyHistoricDraft(view: CatalogView, serverRevision: number) {
    const draft = structuredClone(view.draft);
    if (!Array.isArray(draft.heroSlides)) draft.heroSlides = [];
    ensurePresentation(draft);
    syncMediaAltTexts(draft);
    state.draft = draft;
    state.preview = null;
    state.notice = "已放入编辑区。保存后才会替换现在正在编辑的内容。";
  }

  function clearNotice() {
    state.notice = "";
    state.requestId = "";
  }

  return {
    state,
    get dirty() {
      return dirty.value;
    },
    get unpublishedLabel() {
      return unpublishedLabel.value;
    },
    load,
    save,
    discard,
    previewDraft,
    publish,
    withdraw,
    resolveUnknown,
    applyHistoricDraft,
    clearNotice,
  };
}

export const catalogStoreKey: InjectionKey<CatalogStore> = Symbol("admin-catalog-store");
