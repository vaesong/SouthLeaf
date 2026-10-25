package cn.zhinan.uniapp.authorization.port;

import java.time.Instant;

/** Scoped current fact result. Unknown authorization and dependency failure are distinct states. */
public sealed interface AuthorizationReadResult {
  enum Outcome {
    VALID,
    INVALID,
    UNKNOWN
  }

  record Decision(
      Outcome outcome,
      PublicUseContext coveredContext,
      Instant validFrom,
      Instant validUntil,
      String authorityRevision,
      String factDigest)
      implements AuthorizationReadResult {}

  record DependencyUnavailable(String category) implements AuthorizationReadResult {}
}
