package cn.zhinan.admincontent.domain;

import java.time.Instant;
import java.util.List;

/** Application read/write models. No transport or database types. */
public final class ContentModels {
  private ContentModels() {}

  public record Variant(
      String variant,
      String objectKey,
      String sha256,
      int byteLength,
      int width,
      int height,
      String contentType) {}

  public record ProcessedImage(
      String sourceFormat,
      String sourceSha256,
      int sourceByteLength,
      int sourceWidth,
      int sourceHeight,
      List<EncodedVariant> variants) {
    public ProcessedImage {
      variants = List.copyOf(variants);
    }
  }

  public record EncodedVariant(Variant metadata, byte[] bytes) {}

  public record MediaAsset(
      String mediaId,
      String status,
      String sourceFormat,
      int sourceWidth,
      int sourceHeight,
      Instant createdAt,
      List<Variant> variants) {
    public MediaAsset {
      variants = List.copyOf(variants);
    }
  }

  public record UploadResult(String outcome, String mediaId, String resultCode, boolean replayed) {}

  public record WorkView(
      String workId,
      int version,
      WorkDraft draft,
      Instant updatedAt,
      String publicationState,
      Integer publishedVersion,
      String releaseId) {}

  public record WorkCreated(String workId, int version, boolean replayed) {}

  public record PublicationResult(
      String commandId,
      String operation,
      String workId,
      int targetVersion,
      String releaseId,
      Instant completedAt,
      boolean replayed) {}

  public record Publishability(boolean publishable, String reason, String gateState) {}

  public record PublicationState(
      String workId, String state, int publishedVersion, String releaseId) {}
}
