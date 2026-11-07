package cn.zhinan.objectstore;

/**
 * A storage refusal. The message is a stable code and never includes a credential or object body.
 */
public final class ObjectStoreException extends RuntimeException {
  private final String code;

  public ObjectStoreException(String code) {
    super(code, null, false, false);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
