package cn.zhinan.admincontent.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** M3 versioned content editor and atomic public catalog publisher ports. */
public final class CatalogPorts {
  private CatalogPorts() {}

  public record DraftRevision(
      int revision, String payloadJson, String updatedBy, Instant updatedAt) {}

  public record DraftVersion(int revision, String updatedBy, Instant updatedAt) {}

  public record CatalogMediaReference(
      String mediaId, String altText, String ownerType, String ownerId, int position) {}

  public record PublishCommand(
      String actorId,
      int expectedRevision,
      String idempotencyKey,
      String requestSha256,
      String payloadJson,
      List<CatalogMediaReference> mediaReferences,
      List<String> featuredWorkIds,
      Instant now) {
    public PublishCommand {
      mediaReferences = List.copyOf(mediaReferences);
      featuredWorkIds = List.copyOf(featuredWorkIds);
    }
  }

  public record PublishResult(
      String commandId,
      String releaseId,
      String contentVersion,
      int revision,
      Instant completedAt,
      boolean replayed) {}

  public interface CatalogDraftRepository {
    Optional<DraftRevision> current();

    List<DraftVersion> history();

    Optional<DraftRevision> version(int revision);

    int save(int expectedRevision, String payloadJson, String actorId, Instant now);
  }

  public interface CatalogPublicationRepository {
    void validateMedia(List<CatalogMediaReference> references);

    void validateWorkReferences(List<String> workIds, Instant now);

    Optional<PublishResult> findReplay(String actorId, String idempotencyKey, String requestSha256);

    PublishResult publish(PublishCommand command);

    Optional<PublishResult> findCommand(String actorId, String idempotencyKey);

    Optional<PublishResult> commandById(String commandId);

    void withdraw(
        int expectedRevision,
        String actorId,
        String idempotencyKey,
        String requestSha256,
        Instant now);
  }
}
