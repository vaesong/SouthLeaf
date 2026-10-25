package cn.zhinan.uniapp.publicquery.port;

/** Storage/dependency failure, never an empty successful publication. */
public final class CatalogStorageException extends RuntimeException {
  public CatalogStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
