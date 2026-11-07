package cn.zhinan.uniapp.media.port;

/**
 * How a published derivative is addressed. File mode returns the API content path and still serves
 * bytes. CDN mode returns a short-lived signed URL and does not serve bytes from this process.
 */
public interface MediaDelivery {
  boolean servesBytes();

  String thumbnailUrl(String mediaId, String objectKey);

  String previewUrl(String mediaId, String objectKey);

  static MediaDelivery apiPaths() {
    return new ApiPathMediaDelivery();
  }
}
