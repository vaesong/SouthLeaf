package cn.zhinan.uniapp.works.application;

import cn.zhinan.uniapp.authorization.port.*;
import cn.zhinan.uniapp.publicquery.PublicQueryException;
import cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot;
import cn.zhinan.uniapp.works.domain.ScopedPublicAuthorization;
import java.time.Clock;
import java.util.*;

public class PublicVisibilityPolicy {
  private final PublicAuthorizationEvaluator authorization;
  private final Clock clock;

  public PublicVisibilityPolicy(PublicAuthorizationEvaluator authorization, Clock clock) {
    this.authorization = authorization;
    this.clock = clock;
  }

  public record Decision(boolean allowed, String revision) {}

  public Decision evaluate(CatalogSnapshot snapshot, CatalogSnapshot.Work work) {
    return evaluateAll(snapshot, List.of(work)).get(work.id());
  }

  public Map<String, Decision> evaluateAll(
      CatalogSnapshot snapshot, List<CatalogSnapshot.Work> works) {
    Map<String, Decision> decisions = new HashMap<>();
    List<PublicUseContext> contexts = new ArrayList<>();
    var now = clock.instant();
    for (var work : works) {
      if (!"published".equals(work.publicationStatus())
          || work.coverMediaId() == null
          || !work.mediaIds().contains(work.coverMediaId())
          || !snapshot.assets().keySet().containsAll(work.mediaIds())) {
        decisions.put(work.id(), new Decision(false, ""));
        continue;
      }
      contexts.add(
          new PublicUseContext(
              work.id(),
              snapshot.releaseId(),
              work.candidateVersion(),
              work.bindingVersion(),
              Set.copyOf(work.mediaIds()),
              work.subjectScopeRef(),
              "PUBLIC_WORK_DISPLAY",
              "WECHAT_MINI_PROGRAM",
              now));
    }
    if (contexts.isEmpty()) return Map.copyOf(decisions);
    var facts = authorization.evaluate(List.copyOf(contexts));
    for (var context : contexts) {
      var fact = facts.get(context.workId());
      if (fact == null || fact instanceof AuthorizationReadResult.DependencyUnavailable)
        throw new PublicQueryException(
            PublicQueryException.ErrorCode.PUBLIC_SERVICE_UNAVAILABLE,
            "Authorization dependency unavailable");
      if (!ScopedPublicAuthorization.permits(context, fact)) {
        decisions.put(context.workId(), new Decision(false, ""));
        continue;
      }
      var decision = (AuthorizationReadResult.Decision) fact;
      decisions.put(
          context.workId(),
          new Decision(true, decision.authorityRevision() + ":" + decision.factDigest()));
    }
    return Map.copyOf(decisions);
  }
}
