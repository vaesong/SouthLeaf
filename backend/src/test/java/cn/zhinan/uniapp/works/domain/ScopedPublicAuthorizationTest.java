package cn.zhinan.uniapp.works.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhinan.uniapp.authorization.port.*;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScopedPublicAuthorizationTest {
  private final Instant start = Instant.parse("2026-01-01T00:00:00Z");
  private final Instant end = start.plusSeconds(3600);

  private PublicUseContext context(Instant t, String media) {
    return new PublicUseContext(
        "work",
        "release1",
        "v1",
        "b1",
        Set.of(media),
        "subject-scope",
        "PUBLIC_WORK_DISPLAY",
        "WECHAT_MINI_PROGRAM",
        t);
  }

  private AuthorizationReadResult decision(PublicUseContext c) {
    return new AuthorizationReadResult.Decision(
        AuthorizationReadResult.Outcome.VALID, c, start, end, "revision-1", "digest");
  }

  @Test
  void permitsOnlyExactServerContextWithinHalfOpenInterval() {
    var c = context(start, "m1");
    assertThat(ScopedPublicAuthorization.permits(c, decision(c))).isTrue();
    var before = context(start.minusNanos(1), "m1");
    assertThat(ScopedPublicAuthorization.permits(before, decision(before))).isFalse();
    var atEnd = context(end, "m1");
    assertThat(ScopedPublicAuthorization.permits(atEnd, decision(atEnd))).isFalse();
  }

  @Test
  void replacementMediaCannotReuseOldContext() {
    assertThat(
            ScopedPublicAuthorization.permits(context(start, "m2"), decision(context(start, "m1"))))
        .isFalse();
  }

  @Test
  void dependencyFailureUnknownAndIncompleteCannotGrant() {
    var c = context(start, "m1");
    assertThat(
            ScopedPublicAuthorization.permits(
                c, new AuthorizationReadResult.DependencyUnavailable("timeout")))
        .isFalse();
    assertThat(
            ScopedPublicAuthorization.permits(
                c,
                new AuthorizationReadResult.Decision(
                    AuthorizationReadResult.Outcome.UNKNOWN, c, start, end, "r", "d")))
        .isFalse();
    assertThat(
            ScopedPublicAuthorization.permits(
                c,
                new AuthorizationReadResult.Decision(
                    AuthorizationReadResult.Outcome.VALID, c, start, end, "", "d")))
        .isFalse();
    assertThat(ScopedPublicAuthorization.permits(null, decision(c))).isFalse();
  }
}
