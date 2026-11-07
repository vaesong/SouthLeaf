package cn.zhinan.objectstore;

/** Bucket host for writes. Public reads use the CDN host, which is not derived here. */
public final class CosHosts {
  private CosHosts() {}

  public static String bucketHost(String bucket, String region) {
    if (bucket == null
        || region == null
        || !bucket.matches("[a-z0-9][a-z0-9-]{1,40}-[0-9]{5,12}")
        || !region.matches("[a-z]{2}-[a-z0-9-]{1,20}"))
      throw new ObjectStoreException("COS_HOST_INVALID");
    return bucket + ".cos." + region + ".myqcloud.com";
  }
}
