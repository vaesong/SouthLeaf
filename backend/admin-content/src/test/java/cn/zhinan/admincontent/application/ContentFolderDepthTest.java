package cn.zhinan.admincontent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentMode;
import cn.zhinan.admincontent.domain.ContentModels.MediaFolder;
import cn.zhinan.admincontent.port.ContentPorts.MediaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContentFolderDepthTest {
  private static final String ROOT = "11111111-1111-4111-8111-111111111111";
  private static final String CHILD = "22222222-2222-4222-8222-222222222222";
  private static final String DEEP = "33333333-3333-4333-8333-333333333333";

  @Test
  void aFourthFolderLevelIsRefused() {
    var media = new Folders();
    media.parents.put(ROOT, null);
    media.parents.put(CHILD, ROOT);
    media.parents.put(DEEP, CHILD);
    var application =
        new ContentApplication(
            null,
            null,
            media,
            null,
            null,
            ContentMode.DEMO,
            Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC));

    MediaFolder created = application.createFolder(CHILD, "第三层");
    assertThat(created.parentId()).isEqualTo(CHILD);
    assertThatThrownBy(() -> application.createFolder(DEEP, "第四层"))
        .isInstanceOf(ContentException.class)
        .extracting(error -> ((ContentException) error).reason())
        .isEqualTo("FOLDER_TOO_DEEP");
    assertThatThrownBy(() -> application.createFolder("44444444-4444-4444-8444-444444444444", "缺失"))
        .extracting(error -> ((ContentException) error).reason())
        .isEqualTo("FOLDER_UNKNOWN");
  }

  private static final class Folders implements MediaRepository {
    private final Map<String, String> parents = new HashMap<>();

    @Override
    public Optional<String> folderParent(String folderId) {
      if (!parents.containsKey(folderId))
        throw new ContentException(
            cn.zhinan.admincontent.domain.ContentFailure.NOT_FOUND, "FOLDER_UNKNOWN");
      return Optional.ofNullable(parents.get(folderId));
    }

    @Override
    public MediaFolder createFolder(String folderId, String parentId, String name, Instant now) {
      parents.put(folderId, parentId);
      return new MediaFolder(folderId, parentId, name);
    }

    @Override
    public java.util.Optional<cn.zhinan.admincontent.port.ContentPorts.StoredUpload> findUpload(
        String actorId, String idempotencyKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public cn.zhinan.admincontent.domain.ContentModels.UploadResult saveReady(
        String actorId,
        String idempotencyKey,
        String requestSha256,
        String mediaId,
        cn.zhinan.admincontent.domain.ContentModels.ProcessedImage image,
        String sourceFileName,
        String folderId,
        Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<MediaFolder> folders() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteFolder(String folderId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void move(String mediaId, String folderId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAsset(String mediaId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean mediaInUse(String mediaId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public cn.zhinan.admincontent.domain.ContentModels.UploadResult saveRejected(
        String actorId, String idempotencyKey, String requestSha256, String code, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Optional<cn.zhinan.admincontent.domain.ContentModels.MediaAsset> find(
        String mediaId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Map<String, cn.zhinan.admincontent.domain.ContentModels.MediaAsset> findAll(
        java.util.Collection<String> mediaIds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Optional<cn.zhinan.admincontent.domain.ContentModels.Variant> variant(
        String mediaId, String variant) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<cn.zhinan.admincontent.domain.ContentModels.MediaAsset> recent(
        int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<cn.zhinan.admincontent.domain.ContentModels.MediaAsset> listInFolder(
        String folderId, int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<cn.zhinan.admincontent.domain.ContentModels.MediaAsset> searchByFileName(
        String escapedContains, int limit) {
      throw new UnsupportedOperationException();
    }
  }
}
