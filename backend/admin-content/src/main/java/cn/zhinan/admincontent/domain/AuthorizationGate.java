package cn.zhinan.admincontent.domain;

import java.time.Instant;

/**
 * Read-only A15 decision input. S2 never creates or edits it through management APIs. Evaluation is
 * pure and fail-closed.
 */
public record AuthorizationGate(
    String authorityRevision,
    String state,
    String subjectScopeRef,
    String mediaScopeSha256,
    String purpose,
    String channel,
    Instant validFrom,
    Instant validUntil,
    String provenance,
    String factSha256) {

  /** Returns null when publishable, otherwise a fixed blocking reason code. */
  public static String blockingReason(
      AuthorizationGate gate,
      String subjectScopeRef,
      String mediaScopeSha256,
      Instant now,
      ContentMode mode) {
    if (gate == null) return "AUTHORIZATION_MISSING";
    if (!mode.acceptedProvenance().equals(gate.provenance())) return "AUTHORIZATION_PROVENANCE";
    if ("UNKNOWN".equals(gate.state())) return "AUTHORIZATION_UNKNOWN";
    if (!"VALID".equals(gate.state())) return "AUTHORIZATION_INVALID";
    if (!ContentRules.PURPOSE.equals(gate.purpose())
        || !ContentRules.CHANNEL.equals(gate.channel())) return "AUTHORIZATION_PURPOSE_CHANNEL";
    if (!gate.subjectScopeRef().equals(subjectScopeRef)) return "AUTHORIZATION_SUBJECT_SCOPE";
    if (!gate.mediaScopeSha256().equals(mediaScopeSha256)) return "AUTHORIZATION_MEDIA_SCOPE";
    if (gate.validFrom() == null
        || gate.validUntil() == null
        || !gate.validFrom().isBefore(gate.validUntil())) return "AUTHORIZATION_WINDOW";
    if (now.isBefore(gate.validFrom())) return "AUTHORIZATION_NOT_YET_VALID";
    if (!now.isBefore(gate.validUntil())) return "AUTHORIZATION_EXPIRED";
    return null;
  }
}
