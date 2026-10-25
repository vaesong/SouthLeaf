package cn.zhinan.uniapp.authorization.port;

import java.time.Instant;
import java.util.Set;

/** Internal server-derived scope. No evidence bodies or customer identity. */
public record PublicUseContext(
    String workId,
    String releaseId,
    String candidateVersion,
    String bindingVersion,
    Set<String> mediaRefs,
    String subjectScopeRef,
    String purpose,
    String channel,
    Instant evaluatedAt) {
  public PublicUseContext {
    mediaRefs = mediaRefs == null ? Set.of() : Set.copyOf(mediaRefs);
  }

  public boolean complete() {
    return nonBlank(workId)
        && nonBlank(releaseId)
        && nonBlank(candidateVersion)
        && nonBlank(bindingVersion)
        && !mediaRefs.isEmpty()
        && mediaRefs.stream().allMatch(PublicUseContext::nonBlank)
        && nonBlank(subjectScopeRef)
        && nonBlank(purpose)
        && nonBlank(channel)
        && evaluatedAt != null;
  }

  private static boolean nonBlank(String s) {
    return s != null && !s.isBlank();
  }
}
