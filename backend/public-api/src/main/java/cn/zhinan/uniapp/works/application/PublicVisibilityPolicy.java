package cn.zhinan.uniapp.works.application;

import cn.zhinan.uniapp.authorization.port.*;
import cn.zhinan.uniapp.publicquery.PublicQueryException;
import cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot;
import cn.zhinan.uniapp.works.domain.ScopedPublicAuthorization;
import java.time.Clock;
import java.util.*;

public class PublicVisibilityPolicy {
  private final PublicAuthorizationEvaluator authorization;
  private final PublicOverlayAuthorizationEvaluator overlayAuthorization;
  private final Clock clock;

  /** V1-only policy: S2 overlay works are never visible without an overlay evaluator. */
  public PublicVisibilityPolicy(PublicAuthorizationEvaluator authorization, Clock clock) {
    this(authorization, null, clock);
  }

  public PublicVisibilityPolicy(
      PublicAuthorizationEvaluator authorization,
      PublicOverlayAuthorizationEvaluator overlayAuthorization,
      Clock clock) {
    this.authorization = authorization;
    this.overlayAuthorization = overlayAuthorization;
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
    List<PublicUseContext> overlayContexts = new ArrayList<>();
    var now = clock.instant();
    var businessIds = new java.util.HashSet<String>();
    snapshot.businesses().forEach(b -> businessIds.add(b.id()));
    for (var work : works) {
      if (!"published".equals(work.publicationStatus())
          || work.coverMediaId() == null
          || !work.mediaIds().contains(work.coverMediaId())
          || !snapshot.assets().keySet().containsAll(work.mediaIds())
          || !businessIds.containsAll(work.businessIds())) {
        decisions.put(work.id(), new Decision(false, ""));
        continue;
      }
      // S2 overlay works are scoped to their own per-work release and their own A15 snapshot.
      String overlayRelease = snapshot.overlayReleases().get(work.id());
      if (overlayRelease != null && overlayAuthorization == null) {
        decisions.put(work.id(), new Decision(false, ""));
        continue;
      }
      (overlayRelease == null ? contexts : overlayContexts)
          .add(
              new PublicUseContext(
                  work.id(),
                  overlayRelease == null ? snapshot.releaseId() : overlayRelease,
                  work.candidateVersion(),
                  work.bindingVersion(),
                  Set.copyOf(work.mediaIds()),
                  work.subjectScopeRef(),
                  "PUBLIC_WORK_DISPLAY",
                  "WECHAT_MINI_PROGRAM",
                  now));
    }
    if (contexts.isEmpty() && overlayContexts.isEmpty()) return Map.copyOf(decisions);
    var facts = new HashMap<String, AuthorizationReadResult>();
    if (!contexts.isEmpty()) facts.putAll(authorization.evaluate(List.copyOf(contexts)));
    if (!overlayContexts.isEmpty())
      facts.putAll(overlayAuthorization.evaluate(List.copyOf(overlayContexts)));
    var all = new ArrayList<PublicUseContext>(contexts);
    all.addAll(overlayContexts);
    for (var context : all) {
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
