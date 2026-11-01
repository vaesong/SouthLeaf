package cn.zhinan.admincontent.port;

import cn.zhinan.admincontent.domain.AuthorizationGate;
import cn.zhinan.admincontent.domain.ContentModels.MediaAsset;
import cn.zhinan.admincontent.domain.ContentModels.MediaFolder;
import cn.zhinan.admincontent.domain.ContentModels.ProcessedImage;
import cn.zhinan.admincontent.domain.ContentModels.PublicationResult;
import cn.zhinan.admincontent.domain.ContentModels.PublicationState;
import cn.zhinan.admincontent.domain.ContentModels.UploadResult;
import cn.zhinan.admincontent.domain.ContentModels.Variant;
import cn.zhinan.admincontent.domain.ContentModels.WorkCreated;
import cn.zhinan.admincontent.domain.WorkDraft;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Ports owned by the content application. Each adapter has exactly one database identity. */
public final class ContentPorts {
  private ContentPorts() {}

  /** Decodes, validates and re-encodes untrusted bytes. Never trusts MIME or file names. */
  public interface ImageProcessor {
    ProcessedImage process(byte[] untrusted, String declaredContentType);
  }

  /** Content-addressed, create-only writes into the private media root. */
  public interface MediaObjectWriter {
    void write(String objectKey, byte[] bytes);

    byte[] read(String objectKey);
  }

  /** Business-writer media facts. */
  public interface MediaRepository {
    Optional<StoredUpload> findUpload(String actorId, String idempotencyKey);

    UploadResult saveReady(
        String actorId,
        String idempotencyKey,
        String requestSha256,
        String mediaId,
        ProcessedImage image,
        String sourceFileName,
        String folderId,
        Instant now);

    List<MediaFolder> folders();

    /** Empty when the folder is at the root. Throws when the folder does not exist. */
    Optional<String> folderParent(String folderId);

    MediaFolder createFolder(String folderId, String parentId, String name, Instant now);

    void deleteFolder(String folderId);

    void move(String mediaId, String folderId);

    /**
     * Removes the library row. Object files stay, so an already published page can still read them.
     */
    void deleteAsset(String mediaId);

    boolean mediaInUse(String mediaId);

    UploadResult saveRejected(
        String actorId, String idempotencyKey, String requestSha256, String code, Instant now);

    Optional<MediaAsset> find(String mediaId);

    Map<String, MediaAsset> findAll(Collection<String> mediaIds);

    Optional<Variant> variant(String mediaId, String variant);

    List<MediaAsset> recent(int limit);

    List<MediaAsset> listInFolder(String folderId, int limit);

    /** Case-insensitive contains match. {@code escapedContains} already escapes LIKE wildcards. */
    List<MediaAsset> searchByFileName(String escapedContains, int limit);
  }

  public record StoredUpload(String requestSha256, UploadResult result) {}

  /** Business-writer draft facts. */
  public interface WorkRepository {
    WorkCreated create(String actorId, String idempotencyKey, String requestSha256, Instant now);

    Optional<StoredWork> find(String workId);

    int saveDraft(String workId, int expectedVersion, WorkDraft draft, String actorId, Instant now);

    List<StoredWork> list(int limit);
  }

  public record StoredWork(String workId, int version, WorkDraft draft, Instant updatedAt) {}

  /** Publication-writer facts: cross-schema single transaction, state and gate reads. */
  public interface PublicationRepository {
    PublicationResult publish(PublicationCommand command);

    PublicationResult withdraw(PublicationCommand command);

    Optional<PublicationResult> findCommand(
        String actorId, String operation, String workId, String idempotencyKey);

    Optional<PublicationResult> commandById(String commandId);

    Map<String, PublicationState> states(Collection<String> workIds);

    Optional<AuthorizationGate> gate(String workId);
  }

  public record PublicationCommand(
      String actorId,
      String operation,
      String workId,
      int expectedVersion,
      String idempotencyKey,
      String requestSha256,
      Instant now) {}
}
