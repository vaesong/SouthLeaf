package cn.zhinan.uniapp.media.port;

/** Addresses a derivative on this process. The path uses the media id, not the storage key. */
public final class ApiPathMediaDelivery implements MediaDelivery {
  @Override
  public boolean servesBytes() {
    return true;
  }

  @Override
  public String thumbnailUrl(String mediaId, String objectKey) {
    return path(mediaId, "thumbnail");
  }

  @Override
  public String previewUrl(String mediaId, String objectKey) {
    return path(mediaId, "preview");
  }

  private static String path(String mediaId, String variant) {
    return "/api/v1/public/media/" + mediaId + "/" + variant + "/content";
  }
}
