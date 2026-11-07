package cn.zhinan.admincontent.adapter.cos;

import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import cn.zhinan.admincontent.port.ContentPorts.MediaObjectWriter;
import cn.zhinan.objectstore.CosHosts;
import cn.zhinan.objectstore.CosObjectGateway;
import cn.zhinan.objectstore.CosSigner;
import cn.zhinan.objectstore.JdkHttpTransport;
import cn.zhinan.objectstore.ObjectKeys;
import cn.zhinan.objectstore.ObjectStoreException;
import java.time.Clock;

/**
 * COS adapter for the existing media writer. Objects are create-only derivatives. Admin preview
 * reads through this writer; it does not mint a public CDN address.
 */
public final class CosMediaObjectWriter implements MediaObjectWriter {
  private final CosObjectGateway gateway;

  public CosMediaObjectWriter(String secretId, String secretKey, String bucket, String region) {
    this(
        new CosObjectGateway(
            new JdkHttpTransport(),
            signer(secretId, secretKey),
            CosHosts.bucketHost(bucket, region),
            Clock.systemUTC()));
  }

  CosMediaObjectWriter(CosObjectGateway gateway) {
    this.gateway = gateway;
  }

  public static void requireLocation(
      String secretId, String secretKey, String bucket, String region) {
    signer(secretId, secretKey);
    CosHosts.bucketHost(bucket, region);
  }

  @Override
  public void write(String objectKey, byte[] bytes) {
    try {
      if (!ObjectKeys.matchesContent(objectKey, bytes))
        throw new ObjectStoreException("OBJECT_KEY_INVALID");
      gateway.putCreateOnly(objectKey, bytes);
    } catch (ObjectStoreException failure) {
      throw mapped(failure, "OBJECT_WRITE");
    }
  }

  @Override
  public byte[] read(String objectKey) {
    try {
      return gateway.get(objectKey);
    } catch (ObjectStoreException failure) {
      throw mapped(failure, "OBJECT_READ");
    }
  }

  private static CosSigner signer(String secretId, String secretKey) {
    try {
      return new CosSigner(secretId, secretKey);
    } catch (ObjectStoreException failure) {
      throw new IllegalArgumentException("COS credential rejected");
    }
  }

  private static ContentException mapped(ObjectStoreException failure, String fallback) {
    String code = failure.code();
    String reason =
        switch (code) {
          case "OBJECT_KEY_INVALID", "OBJECT_CONFLICT", "OBJECT_MISSING" -> code;
          default -> fallback;
        };
    return new ContentException(ContentFailure.STORAGE_UNAVAILABLE, reason);
  }
}
