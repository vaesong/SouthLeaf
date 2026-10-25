package cn.zhinan.uniapp.works.domain;

import cn.zhinan.uniapp.authorization.port.AuthorizationReadResult;
import cn.zhinan.uniapp.authorization.port.PublicUseContext;

/** Pure C1 predicate. A true value is necessary, not sufficient for publication or currentness. */
public final class ScopedPublicAuthorization {
  private ScopedPublicAuthorization() {}

  public static boolean permits(PublicUseContext requested, AuthorizationReadResult result) {
    if (requested == null
        || !requested.complete()
        || !"PUBLIC_WORK_DISPLAY".equals(requested.purpose())
        || !"WECHAT_MINI_PROGRAM".equals(requested.channel())
        || !(result instanceof AuthorizationReadResult.Decision decision)
        || decision.outcome() != AuthorizationReadResult.Outcome.VALID
        || !requested.equals(decision.coveredContext())
        || decision.authorityRevision() == null
        || decision.authorityRevision().isBlank()
        || decision.factDigest() == null
        || decision.factDigest().isBlank()
        || decision.validFrom() == null
        || decision.validUntil() == null
        || !decision.validFrom().isBefore(decision.validUntil())) return false;
    return !requested.evaluatedAt().isBefore(decision.validFrom())
        && requested.evaluatedAt().isBefore(decision.validUntil());
  }
}
