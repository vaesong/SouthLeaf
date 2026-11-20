package cn.zhinan.uniapp.media.adapter.cdn;

import cn.zhinan.objectstore.CosSigner;
import cn.zhinan.objectstore.ObjectKeys;
import cn.zhinan.uniapp.media.port.MediaDelivery;
import java.time.Clock;
import java.time.Duration;

/**
 * Mints a signed CDN URL for one derivative object. The URL expires, so a later catalog that no
 * longer names the image also stops issuing new reads. Bytes already fetched are not recalled, and
 * this process does not proxy them.
 *
 * <p>The URL host is the CDN origin. The signed host is the COS bucket host: a COS-type CDN origin
 * always presents that host when it pulls the object, and the console does not allow another one.
 */
public final class SignedCdnMediaDelivery implements MediaDelivery {
  private final String origin;
  private final String signatureHost;
  private final CosSigner signer;
  private final Duration ttl;
  private final Clock clock;

  public SignedCdnMediaDelivery(
      String origin, String signatureHost, CosSigner signer, Duration ttl, Clock clock) {
    if (origin == null || origin.isBlank() || signatureHost == null || signatureHost.isBlank())
      throw new IllegalArgumentException("CDN signature target is incomplete");
    this.origin = origin;
    this.signatureHost = signatureHost;
    this.signer = signer;
    this.ttl = ttl;
    this.clock = clock;
  }

  @Override
  public boolean servesBytes() {
    return false;
  }

  @Override
  public String thumbnailUrl(String mediaId, String objectKey) {
    return url(objectKey);
  }

  @Override
  public String previewUrl(String mediaId, String objectKey) {
    return url(objectKey);
  }

  private String url(String objectKey) {
    String key = ObjectKeys.require(objectKey);
    long start = Math.max(0, clock.instant().getEpochSecond() - 60);
    long end = clock.instant().getEpochSecond() + ttl.toSeconds();
    return origin
        + "/"
        + key
        + "?"
        + signer.signedQuery("GET", "/" + key, signatureHost, start, end);
  }
}
