const BEIJING_OFFSET_MS = 8 * 60 * 60 * 1000;
const WEEKDAYS = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

export function beijingParts(value: string | Date) {
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  const shifted = new Date(date.getTime() + BEIJING_OFFSET_MS);
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth() + 1,
    day: shifted.getUTCDate(),
    weekday: WEEKDAYS[shifted.getUTCDay()] ?? "其他",
    hour: shifted.getUTCHours(),
    minute: shifted.getUTCMinutes(),
  };
}

function pad(value: number) {
  return String(value).padStart(2, "0");
}

export function formatDate(value: string): string {
  const parts = beijingParts(value);
  if (!parts) return "日期待定";
  return `${parts.month} 月 ${parts.day} 日`;
}

export function formatDateTime(value: string): string {
  const parts = beijingParts(value);
  if (!parts) return "时间待定";
  return `${parts.month} 月 ${parts.day} 日 ${parts.weekday} ${pad(parts.hour)}:${pad(parts.minute)}`;
}

export function formatClock(value: string): string {
  const parts = beijingParts(value);
  if (!parts) return "时间待定";
  return `${pad(parts.hour)}:${pad(parts.minute)}`;
}

/** Expiry relative to now, always in Beijing calendar days. */
function sameCalendarDay(
  left: NonNullable<ReturnType<typeof beijingParts>>,
  right: NonNullable<ReturnType<typeof beijingParts>>,
) {
  return left.year === right.year && left.month === right.month && left.day === right.day;
}

export function formatRelative(value: string, now = new Date()): string {
  const target = new Date(value);
  const parts = beijingParts(value);
  const today = beijingParts(now);
  if (!parts || !today || Number.isNaN(target.getTime())) return "时间待定";
  const clock = `${pad(parts.hour)}:${pad(parts.minute)}`;
  if (sameCalendarDay(parts, today)) {
    const delta = target.getTime() - now.getTime();
    if (delta <= 0) return "已过期";
    if (delta < 60 * 60 * 1000) return "不到 1 小时后过期";
    const hours = Math.max(1, Math.round(delta / (60 * 60 * 1000)));
    return `${hours} 小时后过期`;
  }
  const tomorrow = beijingParts(new Date(now.getTime() + 24 * 60 * 60 * 1000));
  if (tomorrow && sameCalendarDay(parts, tomorrow)) return `明天 ${clock} 过期`;
  return `${formatDate(value)} 过期`;
}

export function greeting(now = new Date()): string {
  const parts = beijingParts(now);
  const hour = parts?.hour ?? 9;
  if (hour < 12) return "早上好";
  if (hour < 18) return "下午好";
  return "晚上好";
}

export function formatTodayLabel(now = new Date()): string {
  const parts = beijingParts(now);
  if (!parts) return "";
  return `${parts.month} 月 ${parts.day} 日 · ${parts.weekday}`;
}

const RESERVATION_STATUS: Record<string, string> = {
  PENDING_CONFIRMATION: "待确认",
  CONFIRMED: "已确认",
  REJECTED: "已婉拒",
  EXPIRED: "已过期",
  CANCELLED: "已取消",
  ACTIVE: "进行中",
};

const RESERVATION_SOURCE: Record<string, string> = {
  CUSTOMER_ONLINE: "小程序",
  STAFF_OFFLINE: "后台",
  MINI_PROGRAM: "小程序",
  WECHAT: "小程序",
  ADMIN: "后台",
};

const QR_STATUS: Record<string, string> = {
  UNCONFIGURED: "未上传",
  PLACEHOLDER: "占位图",
  CONFIRMED: "已上传",
};

const MEDIA_STATUS: Record<string, string> = {
  READY: "处理完成",
  PROCESSING: "处理中",
  PENDING: "处理中",
  FAILED: "处理失败",
};

const NOTIFICATION_INTENT: Record<string, string> = {
  RESERVATION_CONFIRMED: "预约已确认",
  RESERVATION_RESCHEDULED: "预约已改期",
  RESERVATION_REJECTED: "预约已婉拒",
  RESERVATION_CANCELLED: "预约已取消",
  RESERVATION_EXPIRED: "预约已过期",
};

const WORK_STATE: Record<string, string> = {
  PUBLISHED: "已发布",
  WITHDRAWN: "已撤下",
  NEVER_PUBLISHED: "草稿",
  DRAFT: "草稿",
};

const PRICE_TYPE: Record<string, string> = {
  FIXED: "固定价",
  FROM: "起价",
  INQUIRY: "到店咨询",
  UNDISCLOSED: "不公开",
};

const BUSINESS_NAME: Record<string, string> = {
  child: "儿童",
  family: "亲子全家福",
  wedding: "婚纱",
  portrait: "写真",
  maternity: "孕照",
  "id-photo": "证件照",
};

function mapped(table: Record<string, string>, value: string | null | undefined): string {
  if (!value) return "其他";
  return table[value] ?? "其他";
}

export function reservationStatusLabel(value: string) {
  return mapped(RESERVATION_STATUS, value);
}
export function reservationSourceLabel(value: string) {
  return mapped(RESERVATION_SOURCE, value);
}
export function qrStatusLabel(value: string) {
  return mapped(QR_STATUS, value);
}
export function mediaStatusLabel(value: string) {
  return mapped(MEDIA_STATUS, value);
}
export function notificationIntentLabel(value: string) {
  return mapped(NOTIFICATION_INTENT, value);
}
export function workStateLabel(value: string) {
  return mapped(WORK_STATE, value);
}
export function priceTypeLabel(value: string) {
  return mapped(PRICE_TYPE, value);
}
export function businessNameLabel(value: string) {
  return mapped(BUSINESS_NAME, value);
}

export function tailNumber(value: string): string {
  const compact = value.replace(/[^0-9A-Za-z]/g, "");
  const tail = compact.slice(-4);
  return tail ? `尾号 ${tail}` : "编号待定";
}

export function formatMoney(value: string): string {
  const normalized = value.replace(/,/g, "").trim();
  if (!/^\d+(\.\d+)?$/.test(normalized)) return value.trim();
  const [whole, fraction] = normalized.split(".");
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return fraction ? `${grouped}.${fraction}` : grouped;
}

export function formatPackagePrice(priceType: string, displayPrice: string): string {
  if (priceType === "INQUIRY") return "到店咨询";
  if (priceType === "UNDISCLOSED") return "不公开";
  const raw = displayPrice.trim();
  const amount = raw ? formatMoney(raw) : "";
  const numeric = /^\d/.test(amount.replace(/,/g, ""));
  if (priceType === "FROM") return numeric ? `¥${amount} 起` : "起价";
  if (priceType === "FIXED") return numeric ? `¥${amount}` : "固定价";
  return "其他";
}

export function countLabel(length: number, max: number): string {
  if (max <= 0 || length / max < 0.8) return "";
  return `${length}/${max}`;
}

/** Shows a server reason only when it is already shop-owner Chinese. */
export function shopReason(
  reason: string | null | undefined,
  fallback = "还有内容没填完，暂时不能发布。",
): string {
  if (!reason) return fallback;
  if (/[\u4e00-\u9fff]/.test(reason) && !/[A-Z]{2,}/.test(reason)) return reason;
  return fallback;
}

const PUBLISH_BLOCK: Record<string, string> = {
  DRAFT_INCOMPLETE: "标题、介绍、肖像授权编号、所属业务、图片和封面都要填齐。",
  AUTHORIZATION_MISSING: "还没有可用的肖像授权。",
  AUTHORIZATION_SUBJECT_SCOPE: "肖像授权编号和这组作品对不上。",
  AUTHORIZATION_EXPIRED: "肖像授权已经过期。",
  AUTHORIZATION_NOT_YET_VALID: "肖像授权还没到生效时间。",
  AUTHORIZATION_INVALID: "肖像授权无效。",
  AUTHORIZATION_UNKNOWN: "肖像授权状态还不明确。",
  AUTHORIZATION_MEDIA_SCOPE: "图片和肖像授权对不上。",
  AUTHORIZATION_PROVENANCE: "肖像授权来源不符合要求。",
  AUTHORIZATION_PURPOSE_CHANNEL: "肖像授权的用途不符合要求。",
  AUTHORIZATION_WINDOW: "肖像授权的有效期不完整。",
  MEDIA_NOT_READY: "有图片还在处理，请稍后再发布。",
};

export function publishBlockLabel(reason: string | null | undefined): string {
  if (!reason) return "暂时无法发布，请检查授权和图片";
  return PUBLISH_BLOCK[reason] ?? "暂时无法发布，请检查授权和图片";
}

const FAILURE_LABEL: Record<string, string> = {
  PROVIDER_TIMEOUT: "发送超时",
  NETWORK_ERROR: "网络中断",
};

export function failureLabel(value: string): string {
  return mapped(FAILURE_LABEL, value);
}
