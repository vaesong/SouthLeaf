package cn.zhinan.adminruntime;

/**
 * How this process is deployed, and therefore which origins and which TLS arrangement are legal.
 *
 * <p>This is a policy value rather than a boolean flag. Each profile answers the two questions the
 * listener cares about — may it bind anywhere but loopback, and must it terminate TLS itself — so
 * adding a future profile is an addition here instead of a new branch in the validation code.
 */
enum DeploymentProfile {
  /**
   * A disposable local fixture. The socket stays on the loopback interface and the process
   * terminates TLS itself with a throwaway certificate, so a browser can reach it without any
   * external proxy. This is what every isolated verification run uses.
   */
  ISOLATED("demo", true, true),

  /**
   * A real deployment. The public origin is a configured name that resolves to the proxy in front
   * of the process; TLS is normally terminated there and the listener socket is not the public
   * address.
   */
  DEPLOYED("release", false, false);

  private final String wireValue;
  private final boolean loopbackListenOnly;
  private final boolean tlsInProcessRequired;

  DeploymentProfile(String wireValue, boolean loopbackListenOnly, boolean tlsInProcessRequired) {
    this.wireValue = wireValue;
    this.loopbackListenOnly = loopbackListenOnly;
    this.tlsInProcessRequired = tlsInProcessRequired;
  }

  String wireValue() {
    return wireValue;
  }

  /** True when the listener may never bind a public address. */
  boolean loopbackListenOnly() {
    return loopbackListenOnly;
  }

  /** True when this profile refuses to start without its own key store. */
  boolean tlsInProcessRequired() {
    return tlsInProcessRequired;
  }

  static DeploymentProfile fromWireValue(String value) {
    for (DeploymentProfile profile : values()) {
      if (profile.wireValue.equals(value)) return profile;
    }
    throw new IllegalArgumentException("Unknown deployment profile");
  }
}
