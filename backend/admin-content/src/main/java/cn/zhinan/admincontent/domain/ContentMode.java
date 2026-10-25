package cn.zhinan.admincontent.domain;

/** Explicit data mode. Demo accepts only TEST_SYNTHETIC facts; production only verified ones. */
public enum ContentMode {
  DEMO("demo", "TEST_SYNTHETIC"),
  PRODUCTION("production", "VERIFIED_AUTHORITY");

  private final String publicMode;
  private final String acceptedProvenance;

  ContentMode(String publicMode, String acceptedProvenance) {
    this.publicMode = publicMode;
    this.acceptedProvenance = acceptedProvenance;
  }

  public String publicMode() {
    return publicMode;
  }

  public String acceptedProvenance() {
    return acceptedProvenance;
  }
}
