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
    AUTHENTICATION_REQUIRED: "登录已失效，请重新登录。",
    CAPABILITY_REQUIRED: "当前账号不能做这个操作。",
    RESOURCE_UNAVAILABLE: "内容不存在或已不可用。",
    VERSION_CONFLICT: "内容已在别处更新，请重新加载后再操作。",
    IDEMPOTENCY_KEY_REUSE: "这次提交和上次内容不一致，请重新保存。",
    NOT_PUBLISHED: "这组作品还没发布，不用撤下。",
    PRECONDITION_REQUIRED: "页面内容已过期，请重新加载。",
    MEDIA_TOO_LARGE: "图片文件超过 30MB。可以先把文件压小，像素不用改。",
    MEDIA_TYPE_UNSUPPORTED: "只支持 JPEG 或 PNG 图片。",
    MEDIA_UNSUPPORTED_TYPE: "只支持 JPEG 或 PNG 图片。",
    MEDIA_REJECTED: "这张图片无法使用，请换一张。",
    MEDIA_NOT_READY: "这张图片还在处理，请稍后再选。",
    INVALID_AUTH_RESPONSE: "登录校验没有完成，请刷新后再试。",
    PUBLICATION_BLOCKED: "还有内容没填完，暂时不能发布。",
    CONTENT_SERVICE_UNAVAILABLE: "暂时连不上，内容没有变化，请稍后再试。",
    NETWORK_ERROR: "网络中断，结果未知。请再试一次。",
    PAYLOAD_TOO_LARGE: "这次提交的内容太大。请少选一些图片后再保存。",
    INVALID_REQUEST: "填写的内容无法保存，请检查标题、介绍、图片和封面。",
  };
  const byReason: Record<string, string> = {
    MEDIA_EDGE: "图片的宽或高超过 12000 像素。缩小长边后再上传。",
    MEDIA_PIXELS: "图片超过 5000 万像素。手机直接导出的原片可以上传，比这更大的需要先缩小。",
    MEDIA_DIMENSIONS: "图片尺寸无法识别，请换一张 JPEG 或 PNG。",
    MEDIA_DERIVATIVE_TOO_LARGE: "压缩后仍然超过 4MB，无法保存。请换一张或先缩小。",
    MEDIA_SIGNATURE: "这张图片已损坏或不是有效的 JPEG / PNG。",
    MEDIA_DECODE: "这张图片已损坏或不是有效的 JPEG / PNG。",
    MEDIA_FORMAT_MISMATCH: "这张图片已损坏或不是有效的 JPEG / PNG。",
    MEDIA_INTEGRITY: "这张图片已损坏或不是有效的 JPEG / PNG。",
    MEDIA_EMPTY: "没有读到图片内容。",
    MEDIA_ALT_CONFLICT: "图片描述和图片库里的记录不一致，请重新加载后再保存。",
    MEDIA_IN_USE: "这张图片还在页面或作品里，先拿掉再删除。",
    FOLDER_NOT_EMPTY: "文件夹里还有内容，先移走或删除后再删文件夹。",
    FOLDER_NAME_TAKEN: "这个文件夹里已经有同名文件夹。",
    FOLDER_NAME_INVALID: "文件夹名称不能为空，也不能包含斜杠。",
    FOLDER_TOO_DEEP: "文件夹最多三层。",
    FOLDER_UNKNOWN: "文件夹不存在。",
    BUSINESS_IN_USE: "这个业务还挂着作品，先改到别的业务再删除。",
    BUSINESS_LIMIT: "业务最多 30 个。",
    AUTHORIZATION_FIELDS: "先填写授权编号并选好图片，再登记授权。",
    HOME_FEATURE_LIMIT: "精选最多 3 个业务、3 个套餐、6 个作品。",
    CHANNEL_UNCONFIRMED: "要公开的社交账号需要已上传的二维码、名称和账号。",
    HOME_BUSINESS_UNAVAILABLE: "精选业务必须是已经公开的业务。",
    HOME_PACKAGE_UNAVAILABLE: "精选套餐必须是已经公开的套餐。",
    PACKAGE_BUSINESS_UNAVAILABLE: "套餐所属的业务还没公开，不能单独公开这个套餐。",
    DRAFT_TITLE_TOO_LONG: "标题最多 80 个字。",
    DRAFT_SUMMARY_TOO_LONG: "介绍最多 400 个字。",
    DRAFT_TITLE_CONTROL: "标题不能换行。",
    DRAFT_SUMMARY_CONTROL: "介绍里有无法保存的特殊字符。普通换行可以保留。",
    DRAFT_SCOPE_INVALID: "授权编号只能包含字母、数字和 . _ : -，最长 64 个字符。",
    DRAFT_BUSINESS_INVALID: "所属业务无效，请重新选择。",
    DRAFT_BUSINESS_LIMIT: "一组作品最多选 3 个业务。",
    DRAFT_COVER_UNBOUND: "封面必须是这组作品里已经选中的一张图片。",
    DRAFT_MEDIA_DUPLICATE: "同一张图片不能加两次。",
    DRAFT_MEDIA_INVALID: "有一张图片无法使用，请重新选择。",
    DRAFT_FIELD_INVALID: "标题或介绍无法保存。标题不能换行，介绍最多 400 个字。",
    DRAFT_JSON_INVALID: "作品内容不完整，请重新填写后再保存。",
    JSON_MALFORMED: "提交的内容无法识别，请重新保存。",
    BODY_TOO_LARGE: "这次提交的内容太大。请少选一些图片后再保存。",
    MEDIA_VARIANTS_INCOMPLETE: "这张图片还没有准备好，请重新上传后再选。",
    PRESENTATION_LINE_TOO_LONG: "这句话太长，请缩短后再保存。",
    WORK_ORDER_INVALID: "这次顺序无法保存，请重新加载后再排。",
    WORK_ORDER_SCOPE_INVALID: "这个分类无法排序。",
    WORK_ORDER_DUPLICATE: "同一组作品不能在顺序里出现两次。",
    WORK_ORDER_CATEGORY: "有一组作品不在这个分类里，顺序没有保存。",
  };
  const base =
    (reason ? byReason[reason] : undefined) ??
    byCode[error.code] ??
    (/[\u4e00-\u9fff]/.test(error.message) ? error.message : "没有完成。请检查填写的内容后再试。");
  const extra =
    reason && reason !== error.code && /[\u4e00-\u9fff]/.test(reason) && !/[A-Z]{2,}/.test(reason)
      ? `（${reason}）`
      : "";
  return {
    text: `${base}${extra}`,
    requestId: error.requestId ?? "",
    unknown: error.status === 0 || error.status >= 500,
  };
}
