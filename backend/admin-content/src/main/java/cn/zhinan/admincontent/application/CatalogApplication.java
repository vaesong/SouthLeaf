package cn.zhinan.admincontent.application;

import cn.zhinan.admincontent.domain.CatalogDraft;
import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import cn.zhinan.admincontent.domain.ContentRules;
import cn.zhinan.admincontent.port.CatalogPorts.CatalogDraftRepository;
import cn.zhinan.admincontent.port.CatalogPorts.CatalogMediaReference;
import cn.zhinan.admincontent.port.CatalogPorts.CatalogPublicationRepository;
import cn.zhinan.admincontent.port.CatalogPorts.DraftRevision;
import cn.zhinan.admincontent.port.CatalogPorts.DraftVersion;
import cn.zhinan.admincontent.port.CatalogPorts.PublishCommand;
import cn.zhinan.admincontent.port.CatalogPorts.PublishResult;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** Coordinates M3 draft revisions and publication without owning JDBC, HTTP, or JSON parsing. */
public final class CatalogApplication {
  private final CatalogDraftRepository drafts;
  private final CatalogPublicationRepository publications;
  private final Clock clock;

  public CatalogApplication(
      CatalogDraftRepository drafts, CatalogPublicationRepository publications, Clock clock) {
    this.drafts = drafts;
    this.publications = publications;
    this.clock = clock;
  }

  public Optional<DraftRevision> currentDraft() {
    return drafts.current();
  }

  public List<DraftVersion> history() {
    return drafts.history();
  }

  public Optional<DraftRevision> version(int revision) {
    return drafts.version(revision);
  }

  public int saveDraft(
      String actorId, int expectedRevision, CatalogDraft draft, String payloadJson) {
    if (actorId == null || actorId.isBlank() || actorId.length() > 128)
      throw new ContentException(ContentFailure.INVALID_REQUEST, "ACTOR_INVALID");
    if (expectedRevision < 0) throw new ContentException(ContentFailure.PRECONDITION_REQUIRED);
    draft.validated();
    validateJson(payloadJson);
    return drafts.save(expectedRevision, payloadJson, actorId, clock.instant());
  }

  public record Preview(boolean publishable, String blockingReason) {}

  public Preview preview(CatalogDraft draft) {
    try {
      draft.publishable();
      publications.validateWorkReferences(draft.featuredWorkIds(), clock.instant());
      publications.validateMedia(toPortMedia(draft));
      return new Preview(true, null);
    } catch (ContentException blocked) {
      return new Preview(false, blocked.reason());
    }
  }

  public PublishResult publish(
      String actorId,
      int expectedRevision,
      String idempotencyKey,
      CatalogDraft draft,
      String canonicalPayload) {
    if (actorId == null || actorId.isBlank() || actorId.length() > 128)
      throw new ContentException(ContentFailure.INVALID_REQUEST, "ACTOR_INVALID");
    if (expectedRevision < 1) throw new ContentException(ContentFailure.PRECONDITION_REQUIRED);
    String key = ContentRules.idempotencyKey(idempotencyKey);
    String digest = ContentRules.sha256("publish|" + expectedRevision);
    var replay = publications.findReplay(actorId, key, digest);
    if (replay.isPresent()) return replay.get();
    draft.publishable();
    validateJson(canonicalPayload);
    var media = toPortMedia(draft);
    return publications.publish(
        new PublishCommand(
            actorId,
            expectedRevision,
            key,
            digest,
            canonicalPayload,
            media,
            draft.featuredWorkIds(),
            clock.instant()));
  }

  public void withdraw(String actorId, int expectedRevision, String idempotencyKey) {
    if (actorId == null || actorId.isBlank() || actorId.length() > 128)
      throw new ContentException(ContentFailure.INVALID_REQUEST, "ACTOR_INVALID");
    if (expectedRevision < 1) throw new ContentException(ContentFailure.PRECONDITION_REQUIRED);
    String key = ContentRules.idempotencyKey(idempotencyKey);
    String digest = ContentRules.sha256("withdraw|" + expectedRevision);
    publications.withdraw(expectedRevision, actorId, key, digest, clock.instant());
  }

  public Optional<PublishResult> findCommand(String actorId, String key) {
    return publications.findCommand(actorId, ContentRules.idempotencyKey(key));
  }

  public Optional<PublishResult> command(String commandId) {
    return publications.commandById(ContentRules.uuid(commandId));
  }

  private static java.util.List<CatalogMediaReference> toPortMedia(CatalogDraft draft) {
    return draft.publishedMediaReferences().stream()
        .map(
            v ->
                new CatalogMediaReference(
                    v.mediaId(), v.altText(), v.ownerType(), v.ownerId(), v.position()))
        .toList();
  }

  private static void validateJson(String json) {
    if (json == null || json.isBlank() || json.length() > 262_144)
      throw new ContentException(ContentFailure.INVALID_REQUEST, "CATALOG_PAYLOAD_INVALID");
  }
}
