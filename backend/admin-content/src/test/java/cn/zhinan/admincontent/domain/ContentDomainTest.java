package cn.zhinan.admincontent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentDomainTest {
  private static final String M1 = "11111111-1111-4111-8111-111111111111";
  private static final String M2 = "22222222-2222-4222-8222-222222222222";
  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

  private static WorkDraft complete() {
    return new WorkDraft(
        "Synthetic work",
        "Synthetic summary",
        "synthetic-subject-1",
        List.of("biz-1"),
        List.of(new WorkDraft.MediaBinding(M1, "alt 1"), new WorkDraft.MediaBinding(M2, "alt 2")),
        M1);
  }

  private static AuthorizationGate gate(String state, String provenance, Instant until) {
    return new AuthorizationGate(
        "rev-1",
        state,
        "synthetic-subject-1",
        ContentRules.mediaScopeDigest(List.of(M2, M1)),
        ContentRules.PURPOSE,
        ContentRules.CHANNEL,
        NOW.minusSeconds(60),
        until,
        provenance,
        "f".repeat(64));
  }

  @Test
  void draftShapeAndCompleteness() {
    complete().requireComplete();
    WorkDraft.empty().validated();
    assertThatThrownBy(() -> WorkDraft.empty().requireComplete())
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("DRAFT_INCOMPLETE");
    assertThatThrownBy(() -> new WorkDraft("t", "s", "x", List.of(), List.of(), M1).validated())
        .hasMessageContaining("DRAFT_COVER_UNBOUND");
    assertThatThrownBy(
            () -> new WorkDraft("t", "s", "x", List.of("a", "a"), List.of(), null).validated())
        .hasMessageContaining("DRAFT_BUSINESS_INVALID");
    assertThatThrownBy(
            () -> new WorkDraft("x".repeat(81), "s", "x", List.of(), List.of(), null).validated())
        .hasMessageContaining("DRAFT_FIELD_INVALID");
  }

  @Test
  void mediaScopeDigestIsOrderIndependent() {
    assertThat(ContentRules.mediaScopeDigest(List.of(M1, M2)))
        .isEqualTo(ContentRules.mediaScopeDigest(List.of(M2, M1)))
        .isNotEqualTo(ContentRules.mediaScopeDigest(List.of(M1)));
  }

  @Test
  void gateFailsClosed() {
    String scope = ContentRules.mediaScopeDigest(List.of(M1, M2));
    var valid = gate("VALID", "TEST_SYNTHETIC", NOW.plusSeconds(60));
    assertThat(
            AuthorizationGate.blockingReason(
                valid, "synthetic-subject-1", scope, NOW, ContentMode.DEMO))
        .isNull();
    assertThat(
            AuthorizationGate.blockingReason(
                null, "synthetic-subject-1", scope, NOW, ContentMode.DEMO))
        .isEqualTo("AUTHORIZATION_MISSING");
    assertThat(
            AuthorizationGate.blockingReason(
                valid, "synthetic-subject-1", scope, NOW, ContentMode.PRODUCTION))
        .isEqualTo("AUTHORIZATION_PROVENANCE");
    assertThat(
            AuthorizationGate.blockingReason(
                gate("UNKNOWN", "TEST_SYNTHETIC", NOW.plusSeconds(60)),
                "synthetic-subject-1",
                scope,
                NOW,
                ContentMode.DEMO))
        .isEqualTo("AUTHORIZATION_UNKNOWN");
    assertThat(AuthorizationGate.blockingReason(valid, "other", scope, NOW, ContentMode.DEMO))
        .isEqualTo("AUTHORIZATION_SUBJECT_SCOPE");
    assertThat(
            AuthorizationGate.blockingReason(
                valid,
                "synthetic-subject-1",
                ContentRules.mediaScopeDigest(List.of(M1)),
                NOW,
                ContentMode.DEMO))
        .isEqualTo("AUTHORIZATION_MEDIA_SCOPE");
    assertThat(
            AuthorizationGate.blockingReason(
                valid, "synthetic-subject-1", scope, NOW.plusSeconds(60), ContentMode.DEMO))
        .isEqualTo("AUTHORIZATION_EXPIRED");
  }

  @Test
  void idempotencyKeyAndIdFormats() {
    assertThat(ContentRules.idempotencyKey("abcdefghijklmnop")).isEqualTo("abcdefghijklmnop");
    assertThatThrownBy(() -> ContentRules.idempotencyKey("short"))
        .isInstanceOf(ContentException.class);
    assertThatThrownBy(() -> ContentRules.uuid("../etc")).isInstanceOf(ContentException.class);
  }
}
