package cn.zhinan.adminsecurity.port;

/** Implementations generate 256-bit opaque secrets and SHA-256 storage digests. */
public interface SessionTokenCodec {
  String generate();

  String digest(String token);
}
