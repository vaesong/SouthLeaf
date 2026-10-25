package cn.zhinan.admincontent.domain;

/** Stable, non-sensitive failure categories. HTTP mapping is owned by the runtime adapter. */
public enum ContentFailure {
  INVALID_REQUEST,
  PRECONDITION_REQUIRED,
  MEDIA_UNSUPPORTED_TYPE,
  MEDIA_TOO_LARGE,
  MEDIA_REJECTED,
  MEDIA_NOT_READY,
  NOT_FOUND,
  VERSION_CONFLICT,
  IDEMPOTENCY_CONFLICT,
  DRAFT_INCOMPLETE,
  PUBLICATION_BLOCKED,
  NOT_PUBLISHED,
  STORAGE_UNAVAILABLE
}
