package cn.zhinan.adminruntime;

import java.net.URI;
import java.nio.file.Path;

/**
 * In-process TLS material for the administrative listener.
 *
 * <p>Exists so the listener sees "there is a key store" or "there is none" instead of three
 * independently nullable fields, and so the password can never leak through {@code toString} into a
 * log line or a stack trace.
 */
record AdminTlsMaterial(Path keyStore, String password, String type, String alias) {
  AdminTlsMaterial {
    if (keyStore == null || !keyStore.isAbsolute())
      throw new IllegalArgumentException("Key store path must be absolute");
    if (password == null || password.isBlank() || password.length() > 4096)
      throw new IllegalArgumentException("Key store password is absent or unusable");
    if (type == null || type.isBlank()) throw new IllegalArgumentException("Key store type");
    if (alias != null && !alias.matches("[A-Za-z0-9._-]{1,64}"))
      throw new IllegalArgumentException("Key alias");
  }

  URI keyStoreUri() {
    return keyStore.toUri();
  }

  @Override
  public String toString() {
    return "AdminTlsMaterial[REDACTED]";
  }
}
