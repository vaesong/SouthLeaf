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

/** Keep this list identical to CatalogPresentation.KEYS. Empty values render as nothing. */
export const PRESENTATION_FIELDS = [
  { group: "底部导航", key: "tabs.home", label: "首页" },
  { group: "底部导航", key: "tabs.works", label: "作品" },
  { group: "底部导航", key: "tabs.services", label: "服务" },
  { group: "底部导航", key: "tabs.about", label: "关于" },
  { group: "底部导航", key: "tabs.mine", label: "我的" },
  { group: "首页", key: "home.themesTitle", label: "主题栏标题" },
  { group: "首页", key: "home.storiesTitle", label: "故事栏标题" },
  { group: "首页", key: "home.allWorks", label: "全部作品" },
  { group: "首页", key: "home.packagesTitle", label: "方案栏标题" },
  { group: "首页", key: "home.allServices", label: "全部服务" },
  { group: "首页", key: "home.signOff", label: "落款" },
  { group: "首页", key: "home.aboutLink", label: "关于入口" },
  { group: "首页", key: "home.story", label: "首页短句" },
  { group: "关于", key: "about.title", label: "页面标题" },
  { group: "关于", key: "about.summary", label: "页面摘要" },
  { group: "关于", key: "about.signOff", label: "理念落款" },
  { group: "关于", key: "about.boundary", label: "页底说明" },
  { group: "关于", key: "about.teamTitle", label: "主理人标题" },
  { group: "关于", key: "about.teamSubtitle", label: "主理人副题" },
  { group: "关于", key: "about.channelsTitle", label: "联系栏标题" },
  { group: "关于", key: "about.placeholderQr", label: "占位二维码说明" },
  { group: "关于", key: "about.channelsEmpty", label: "没有联系方式时" },
  { group: "关于", key: "about.phoneLabel", label: "电话标签" },
  { group: "关于", key: "about.call", label: "拨打" },
  { group: "关于", key: "about.addressLabel", label: "地址标签" },
  { group: "关于", key: "about.copy", label: "复制" },
  { group: "关于", key: "about.hoursLabel", label: "营业时间标签" },
  { group: "关于", key: "about.qrHint", label: "二维码提示" },
  { group: "关于", key: "about.copyAccountFailed", label: "复制账号失败" },
  { group: "关于", key: "about.copyAddressFailed", label: "复制地址失败" },
  { group: "作品", key: "works.title", label: "页面标题" },
  { group: "作品", key: "works.summary", label: "页面摘要" },
  { group: "作品", key: "works.all", label: "全部" },
  { group: "作品", key: "works.empty", label: "没有作品时" },
  { group: "作品", key: "works.sortNewest", label: "最新发布" },
  { group: "作品", key: "works.sortOldest", label: "最早发布" },
  { group: "作品", key: "works.loadMore", label: "加载更多" },
  { group: "作品", key: "works.layoutEditorial", label: "等高排版" },
  { group: "作品", key: "works.layoutGrid", label: "瀑布排版" },
  { group: "作品", key: "workDetail.dockTitle", label: "详情底栏标题" },
  { group: "作品", key: "workDetail.dockSubtitle", label: "详情底栏说明" },
  { group: "作品", key: "workDetail.book", label: "预约按钮" },
  { group: "作品", key: "workDetail.share", label: "分享按钮" },
  { group: "作品", key: "workDetail.photoSuffix", label: "影像数量后缀" },
  { group: "作品", key: "workDetail.layoutAlbum", label: "画册排版" },
  { group: "作品", key: "workDetail.layoutOverview", label: "总览排版" },
  { group: "服务", key: "services.title", label: "页面标题" },
  { group: "服务", key: "services.summary", label: "页面摘要" },
  { group: "服务", key: "services.view", label: "查看方案" },
  { group: "服务", key: "services.empty", label: "没有服务时" },
  { group: "服务", key: "services.loadMore", label: "加载更多" },
  { group: "服务", key: "packageDetail.book", label: "预约按钮" },
  { group: "服务", key: "packageDetail.share", label: "分享" },
  { group: "服务", key: "packageDetail.durationLabel", label: "时长标签" },
  { group: "服务", key: "packageDetail.includesMetric", label: "包含数量标签" },
  { group: "服务", key: "packageDetail.noticesMetric", label: "须知数量标签" },
  { group: "服务", key: "packageDetail.audiencePrefix", label: "适合人群前缀" },
  { group: "服务", key: "packageDetail.includesTitle", label: "服务包含标题" },
  { group: "服务", key: "packageDetail.tipsTitle", label: "温馨提示标题" },
  { group: "服务", key: "packageDetail.noticesTitle", label: "拍摄须知标题" },
  { group: "服务", key: "packageDetail.priceLabel", label: "价格标签" },
  { group: "服务", key: "packageDetail.priceInquiry", label: "询价时的价格句" },
  { group: "服务", key: "packageDetail.priceFromMissing", label: "起价缺失时" },
  { group: "服务", key: "packageDetail.priceFixedMissing", label: "定价缺失时" },
  { group: "服务", key: "packageDetail.priceUnpublished", label: "价格未公布" },
  { group: "预约", key: "booking.title", label: "页面标题" },
  { group: "预约", key: "booking.summary", label: "页面摘要" },
  { group: "预约", key: "booking.closedTitle", label: "未开放标题" },
  { group: "预约", key: "booking.closedBody", label: "未开放说明" },
  { group: "预约", key: "booking.submitHint", label: "提交说明" },
  { group: "预约", key: "booking.pendingHint", label: "待确认说明" },
  { group: "预约", key: "booking.confirmedHint", label: "已确认说明" },
  { group: "预约", key: "booking.rejectedHint", label: "未通过说明" },
  { group: "预约", key: "booking.expiredHint", label: "已过期说明" },
  { group: "预约", key: "booking.cancelledHint", label: "已取消说明" },
  { group: "预约", key: "booking.wechatLabel", label: "微信标签" },
  { group: "预约", key: "booking.copyWechat", label: "复制微信" },
  { group: "预约", key: "booking.copyWechatFailed", label: "复制微信失败" },
  { group: "预约", key: "booking.phoneUnavailable", label: "电话未公开" },
  { group: "预约", key: "booking.wechatUnavailable", label: "微信未公开" },
  { group: "预约", key: "booking.phoneLabel", label: "电话标签" },
  { group: "预约", key: "booking.call", label: "拨打电话" },
  { group: "预约", key: "booking.backToWork", label: "返回作品" },
  { group: "预约", key: "booking.packagesTitle", label: "套餐标题" },
  { group: "预约", key: "booking.dateTitle", label: "日期标题" },
  { group: "预约", key: "booking.rangeStart", label: "开始" },
  { group: "预约", key: "booking.rangeEnd", label: "结束" },
  { group: "预约", key: "booking.slotsTitle", label: "时段标题" },
  { group: "预约", key: "booking.submit", label: "提交按钮" },
  { group: "预约", key: "booking.mineEntry", label: "我的预约入口" },
  { group: "预约", key: "booking.statusPending", label: "待确认" },
  { group: "预约", key: "booking.statusConfirmed", label: "已确认" },
  { group: "预约", key: "booking.statusRejected", label: "未接受" },
  { group: "预约", key: "booking.statusExpired", label: "已过期" },
  { group: "预约", key: "booking.statusCancelled", label: "已取消" },
  { group: "我的", key: "mine.title", label: "页面标题" },
  { group: "我的", key: "mine.summary", label: "页面摘要" },
  { group: "我的", key: "mine.welcome", label: "欢迎句" },
  { group: "我的", key: "mine.signedOutTitle", label: "未登录标题" },
  { group: "我的", key: "mine.signedOutHint", label: "未登录说明" },
  { group: "我的", key: "mine.closedTitle", label: "未开放标题" },
  { group: "我的", key: "mine.closedBody", label: "未开放说明" },
  { group: "我的", key: "mine.empty", label: "没有预约时" },
  { group: "我的", key: "mine.book", label: "去预约" },
  { group: "我的", key: "mine.upcomingTitle", label: "近期安排标题" },
  { group: "我的", key: "mine.newBooking", label: "新预约" },
  { group: "我的", key: "mine.holdPrefix", label: "申请保留前缀" },
  { group: "我的", key: "mine.menuBooking", label: "预约入口" },
  { group: "我的", key: "mine.menuServices", label: "服务入口" },
  { group: "我的", key: "mine.menuAbout", label: "关于入口" },
  { group: "我的", key: "mine.scopeHint", label: "列表范围说明" },
  { group: "预约详情", key: "requestDetail.title", label: "页标题" },
  { group: "预约详情", key: "requestDetail.holdPrefix", label: "申请保留前缀" },
  { group: "预约详情", key: "requestDetail.requestedTitle", label: "申请时段标题" },
  { group: "预约详情", key: "requestDetail.confirmedTitle", label: "确认约定标题" },
  { group: "预约详情", key: "requestDetail.slotPrefix", label: "时段前缀" },
  { group: "预约详情", key: "requestDetail.amountPrefix", label: "金额前缀" },
  { group: "预约详情", key: "requestDetail.anotherSlot", label: "再看时段" },
  { group: "其他", key: "settlement.notice", label: "不收款说明" },
  { group: "其他", key: "hero.fallbackSubtitle", label: "未配置轮播时的副题" },
  { group: "其他", key: "hero.fallbackAction", label: "未配置轮播时的动作" },
] as const;

export function presentationGroups(): {
  name: string;
  fields: (typeof PRESENTATION_FIELDS)[number][];
}[] {
  const groups: { name: string; fields: (typeof PRESENTATION_FIELDS)[number][] }[] = [];
  for (const field of PRESENTATION_FIELDS) {
    const found = groups.find((group) => group.name === field.group);
    if (found) found.fields.push(field);
    else groups.push({ name: field.group, fields: [field] });
  }
  return groups;
}

export function ensurePresentation(draft: CatalogDraftInput): void {
  const current =
    draft.presentation && typeof draft.presentation === "object" ? draft.presentation : {};
  const next: Record<string, string> = {};
  for (const field of PRESENTATION_FIELDS) {
    const value = current[field.key];
    next[field.key] = typeof value === "string" ? value.slice(0, 400) : "";
  }
  draft.presentation = next;
}

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
  ensurePresentation(draft);
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
