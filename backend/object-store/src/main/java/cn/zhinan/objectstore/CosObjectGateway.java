package cn.zhinan.objectstore;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Create-only COS writes and private reads of derivative objects. A conflicting existing object is
 * accepted only when its bytes are identical. This gateway does not publish a URL.
 */
public final class CosObjectGateway {
  private final ObjectTransport transport;
  private final CosSigner signer;
  private final String host;
  private final Clock clock;

  public CosObjectGateway(ObjectTransport transport, CosSigner signer, String host, Clock clock) {
    if (transport == null || signer == null || clock == null || host == null || host.isBlank())
      throw new ObjectStoreException("COS_HOST_INVALID");
    this.transport = transport;
    this.signer = signer;
    this.host = host;
    this.clock = clock;
  }

  public void putCreateOnly(String key, byte[] bytes) {
    String objectKey = ObjectKeys.require(key);
    if (bytes == null || bytes.length == 0 || bytes.length > ObjectKeys.MAX_BYTES)
      throw new ObjectStoreException("OBJECT_BYTES_INVALID");
    if (!ObjectKeys.matchesContent(objectKey, bytes))
      throw new ObjectStoreException("OBJECT_KEY_INVALID");
    String contentType = ObjectKeys.contentType(objectKey);
    var headers =
        Map.of("host", host, "content-type", contentType, "x-cos-forbid-overwrite", "true");
    ObjectTransport.Response response =
        transport.exchange("PUT", url(objectKey), signed(headers, "put", objectKey), bytes);
    if (response.status() == 200) return;
    if (response.status() == 409) {
      byte[] existing = readRaw(objectKey);
      if (existing.length == bytes.length && ObjectKeys.matchesContent(objectKey, existing)) return;
      throw new ObjectStoreException("OBJECT_CONFLICT");
    }
    throw new ObjectStoreException("OBJECT_WRITE");
  }

  public byte[] get(String key) {
    String objectKey = ObjectKeys.require(key);
    byte[] body = readRaw(objectKey);
    if (!ObjectKeys.matchesContent(objectKey, body)) throw new ObjectStoreException("OBJECT_READ");
    return body;
  }

  private byte[] readRaw(String objectKey) {
    ObjectTransport.Response response =
        transport.exchange(
            "GET", url(objectKey), signed(Map.of("host", host), "get", objectKey), new byte[0]);
    if (response.status() == 404) throw new ObjectStoreException("OBJECT_MISSING");
    if (response.status() != 200) throw new ObjectStoreException("OBJECT_READ");
    byte[] body = response.body();
    if (body.length == 0 || body.length > ObjectKeys.MAX_BYTES)
      throw new ObjectStoreException("OBJECT_READ");
    return body;
  }

  private Map<String, String> signed(Map<String, String> headers, String method, String key) {
    long now = clock.instant().getEpochSecond();
    long start = Math.max(0, now - 60);
    String authorization =
        signer.authorization(
            method, "/" + key, headers, start, now + Duration.ofMinutes(5).toSeconds());
    var sent = new java.util.LinkedHashMap<String, String>();
    headers.forEach(
        (name, value) -> {
          if (!"host".equals(name)) sent.put(name, value);
        });
    sent.put("Authorization", authorization);
    return Map.copyOf(sent);
  }

  private String url(String key) {
    return "https://" + host + "/" + key;
  }
}
