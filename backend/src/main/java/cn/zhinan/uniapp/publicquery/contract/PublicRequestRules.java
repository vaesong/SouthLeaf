package cn.zhinan.uniapp.publicquery.contract;

import cn.zhinan.uniapp.publicquery.PublicQueryException;
import java.util.List;

/** Shared public input rules; neither query nor media use cases depend on the other service. */
public final class PublicRequestRules {
  private PublicRequestRules() {}

  public static void requiredId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
  }

  public static void variant(String value) {
    if (value == null || !List.of("preview", "thumbnail").contains(value)) throw invalid();
  }

  public static PublicQueryException invalid() {
    return new PublicQueryException(
        PublicQueryException.ErrorCode.PUBLIC_QUERY_INVALID, "Invalid public query");
  }

  public static PublicQueryException unavailable() {
    return new PublicQueryException(
        PublicQueryException.ErrorCode.PUBLIC_RESOURCE_UNAVAILABLE, "Public resource unavailable");
  }
}
