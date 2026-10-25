import { AdminApiError } from "../auth/api";
import { ContentApiError } from "./api";

/** Maps server failure codes to operator-facing text without inventing a success. */
export function describeFailure(error: unknown): {
  text: string;
  requestId: string;
  unknown: boolean;
} {
  if (!(error instanceof AdminApiError))
    return { text: "發生未預期的錯誤。", requestId: "", unknown: true };
  const reason = error instanceof ContentApiError ? error.reason : undefined;
  const byCode: Record<string, string> = {
    AUTHENTICATION_REQUIRED: "工作階段已失效，請重新登入。",
    CAPABILITY_REQUIRED: "目前帳號沒有執行此操作的能力。",
    RESOURCE_UNAVAILABLE: "資源不存在或已不可用。",
    VERSION_CONFLICT: "內容已被其他人更新；請重新載入最新修訂後再操作。",
    IDEMPOTENCY_KEY_REUSE: "同一操作鍵已用於不同內容；請重新發起操作。",
    NOT_PUBLISHED: "作品目前未發布，無需撤下。",
    PRECONDITION_REQUIRED: "缺少修訂前置條件，請重新載入。",
    MEDIA_TOO_LARGE: "圖片超過 4 MiB 上限。",
    MEDIA_UNSUPPORTED_TYPE: "僅支援 JPEG 或 PNG 圖片。",
    MEDIA_REJECTED: "圖片無法通過安全處理（尺寸、格式或內容無效）。",
    MEDIA_NOT_READY: "所選圖片尚未就緒。",
    PUBLICATION_BLOCKED: "發布門禁未通過。",
    CONTENT_SERVICE_UNAVAILABLE: "內容服務或授權依賴暫時不可用；結果未變更，請稍後重試。",
    NETWORK_ERROR: "連線中斷，結果未知。請以相同操作重試（將沿用同一操作鍵）。",
  };
  const base = byCode[error.code] ?? error.message;
  return {
    text: reason && reason !== error.code ? `${base}（${reason}）` : base,
    requestId: error.requestId ?? "",
    unknown: error.status === 0 || error.status >= 500,
  };
}
