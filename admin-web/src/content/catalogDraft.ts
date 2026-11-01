import type { CatalogDraftInput } from "./api";

export const BUSINESS_IDS = [
  "child",
  "family",
  "wedding",
  "portrait",
  "maternity",
  "id-photo",
] as const;

export const FEATURED_LIMIT = 3;
export const FEATURED_WORK_LIMIT = 6;
export const HERO_LIMIT = 5;

export const PRICE_TYPES = [
  { value: "FIXED", label: "固定价" },
  { value: "FROM", label: "起价" },
  { value: "INQUIRY", label: "到店咨询" },
  { value: "UNDISCLOSED", label: "不公开" },
] as const;

export const QR_STATUSES = [
  { value: "UNCONFIGURED", label: "未上传" },
  { value: "PLACEHOLDER", label: "占位图" },
  { value: "CONFIRMED", label: "已上传" },
] as const;

export const CHANNEL_PLATFORMS = [
  { value: "wechat", label: "微信" },
  { value: "douyin", label: "抖音" },
  { value: "xiaohongshu", label: "小红书" },
] as const;

export function lines(value: string): string[] {
  return value
    .split("\n")
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

export function joinLines(value: string[]): string {
  return value.join("\n");
}

export function emptyToNull(value: string | null | undefined): string | null {
  if (value === null || value === undefined) return null;
  const trimmed = value.trim();
  return trimmed.length === 0 ? null : trimmed;
}

export function referencedMediaIds(draft: CatalogDraftInput): string[] {
  const ids = [
    draft.brand.logoMediaId,
    ...draft.team.map((item) => item.portraitMediaId),
    ...draft.channels.map((item) => item.qrMediaId),
    ...draft.businesses.map((item) => item.coverMediaId),
    ...draft.packages.map((item) => item.coverMediaId),
    ...(draft.heroSlides ?? []).map((item) => item.mediaId),
  ].filter((id): id is string => typeof id === "string" && id.length > 0);
  return [...new Set(ids)];
}

export function syncMediaAltTexts(draft: CatalogDraftInput): void {
  draft.mediaAltTexts = Object.fromEntries(referencedMediaIds(draft).map((id) => [id, ""]));
}

/** Keeps at most `max` ids. Adding a selected id removes it; a fourth id is ignored. */
export function toggleLimitedIds(ids: string[], id: string, max: number): string[] {
  const index = ids.indexOf(id);
  if (index >= 0) return ids.filter((item) => item !== id);
  if (ids.length >= max) return ids;
  return [...ids, id];
}

export function unpublishedHint(dirty: boolean, savedUnpublished: boolean): string {
  if (dirty) return "有修改尚未保存";
  if (savedUnpublished) return "已保存，尚未发布";
  return "";
}

export function priceNeedsAmount(priceType: string): boolean {
  return priceType === "FIXED" || priceType === "FROM";
}

export function normalizeDraft(draft: CatalogDraftInput): void {
  if (!Array.isArray(draft.heroSlides)) draft.heroSlides = [];
  draft.heroSlides.forEach((item, index) => {
    item.position = index;
    item.subtitle = item.subtitle ?? "";
    if (item.target?.type === "ABOUT") item.target = { type: "ABOUT", id: "about" };
  });
  draft.brand.logoMediaId = emptyToNull(draft.brand.logoMediaId);
  draft.team.forEach((item, index) => {
    item.position = index;
    item.portraitMediaId = emptyToNull(item.portraitMediaId);
  });
  draft.channels.forEach((item, index) => {
    item.position = index;
    item.qrMediaId = emptyToNull(item.qrMediaId);
  });
  [...draft.businesses]
    .sort((a, b) => a.position - b.position)
    .forEach((item, index) => {
      item.position = index;
      item.coverMediaId = emptyToNull(item.coverMediaId);
    });
  draft.packages.forEach((item, index) => {
    item.position = index;
    item.coverMediaId = emptyToNull(item.coverMediaId);
    if ((item.durationMinutes as unknown) === "" || item.durationMinutes === undefined) {
      item.durationMinutes = null;
    }
    if (!priceNeedsAmount(item.priceType)) item.displayPrice = "";
  });
  syncMediaAltTexts(draft);
}

export function moveItem<T>(items: T[], index: number, offset: number): void {
  const target = index + offset;
  if (target < 0 || target >= items.length) return;
  const [item] = items.splice(index, 1);
  if (item !== undefined) items.splice(target, 0, item);
}

export function snapshotOf(draft: CatalogDraftInput): string {
  return JSON.stringify(draft);
}
