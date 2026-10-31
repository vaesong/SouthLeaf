import { AdminApiError } from "../auth/api";
import { ContentApiError } from "./api";

/** Maps server failure codes to operator-facing text without inventing a success. */
export function describeFailure(error: unknown): {
  text: string;
  requestId: string;
  unknown: boolean;
} {
  if (!(error instanceof AdminApiError))
    return { text: "发生未预期的错误。", requestId: "", unknown: true };
  const reason = error instanceof ContentApiError ? error.reason : undefined;
  const byCode: Record<string, string> = {
    AUTHENTICATION_REQUIRED: "工作阶段已失效，请重新登录。",
    CAPABILITY_REQUIRED: "当前账号没有执行此操作的能力。",
    RESOURCE_UNAVAILABLE: "资源不存在或已不可用。",
    VERSION_CONFLICT: "内容已被其他人更新；请重新加载最新修订后再操作。",
    IDEMPOTENCY_KEY_REUSE: "同一操作键已用于不同内容；请重新发起操作。",
    NOT_PUBLISHED: "作品目前未发布，无需撤下。",
    PRECONDITION_REQUIRED: "缺少修订前置条件，请重新加载。",
    MEDIA_TOO_LARGE: "圖片超過 4 MiB 上限。",
    MEDIA_UNSUPPORTED_TYPE: "仅支持 JPEG 或 PNG 图片。",
    MEDIA_REJECTED: "圖片無法通過安全處理（尺寸、格式或內容無效）。",
    MEDIA_NOT_READY: "所選圖片尚未就緒。",
    PUBLICATION_BLOCKED: "发布门禁未通过。",
    CONTENT_SERVICE_UNAVAILABLE: "内容服务或授权依赖暂时不可用；结果未变更，请稍后重试。",
    NETWORK_ERROR: "连接中断，结果未知。请以相同操作重试（将沿用同一操作键）。",
  };
  const base = byCode[error.code] ?? error.message;
  return {
    text: reason && reason !== error.code ? `${base}（${reason}）` : base,
    requestId: error.requestId ?? "",
    unknown: error.status === 0 || error.status >= 500,
  };
}
