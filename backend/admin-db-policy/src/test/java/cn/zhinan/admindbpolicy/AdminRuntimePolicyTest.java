package cn.zhinan.admindbpolicy;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminRuntimePolicyTest {
  private static final String URL =
      "jdbc:mysql://localhost:3306/southleaf_admin_it?sslMode=REQUIRED";

  @Test
  void demoIsCanonicalAndMigrationStillStrict() {
    assertThat(AdminRuntimePolicy.canonicalUrl("demo", "southleaf_admin_it", URL))
        .contains(
            "sslMode=REQUIRED",
            "connectTimeout=5000",
            "socketTimeout=5000",
            "allowPublicKeyRetrieval=false");
    assertThatThrownBy(() -> AdminMigrationPolicy.validateMigrationUrl(URL))
        .isInstanceOf(IllegalArgumentException.class);
    String strong = "jdbc:mysql://db.example/southleaf_admin?sslMode=VERIFY_IDENTITY";
    assertThat(AdminRuntimePolicy.canonicalUrl("production", "southleaf_admin", strong))
        .contains("VERIFY_IDENTITY");
    AdminMigrationPolicy.validateMigrationUrl(strong);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "&sslMode=VERIFY_IDENTITY",
        "&%73slMode=REQUIRED",
        "&username=secret",
        "&password=secret",
        "&socketFactory=evil",
        "&allowPublicKeyRetrieval=true",
        "&connectionTimeZone=SERVER",
        "&forceConnectionTimeZoneToSession=false",
        "&characterEncoding=latin1",
        "&connectTimeout=0",
        "&connectTimeout=999",
        "&connectTimeout=15001",
        "&socketTimeout=-1",
        "&socketTimeout=99999999999",
        "&connectTimeout=05000",
        "&connectTimeout=1e3",
        "&connectTimeout=5000&CONNECTTIMEOUT=6000",
        "#secret",
        "&foo=x",
        "&connectTimeout",
        "&",
        "&connectTimeout=%35%30%30%30"
      })
  void rejectsMalformedOptions(String suffix) {
    String url = suffix.isEmpty() ? URL.replace("?sslMode=REQUIRED", "") : URL + suffix;
    assertThatThrownBy(() -> AdminRuntimePolicy.canonicalUrl("demo", "southleaf_admin_it", url))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DISABLED", "PREFERRED", "required", "VERIFY_IDENTITY ", "", "VERIFY_CA"})
  void productionNeverAcceptsWeakerOrAmbiguousTls(String tls) {
    assertThatThrownBy(
            () ->
                AdminRuntimePolicy.canonicalUrl(
                    "production",
                    "southleaf_admin",
                    "jdbc:mysql://localhost/southleaf_admin?sslMode=" + tls))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:mysql://u:p@localhost/southleaf_admin_it?sslMode=REQUIRED",
        "jdbc:mysql://localhost,other/southleaf_admin_it?sslMode=REQUIRED",
        "jdbc:mysql:loadbalance://localhost/southleaf_admin_it?sslMode=REQUIRED",
        "jdbc:mysql://localhost:0/southleaf_admin_it?sslMode=REQUIRED",
        "jdbc:mysql://localhost:65536/southleaf_admin_it?sslMode=REQUIRED",
        "jdbc:mysql://localhost/southleaf_public?sslMode=REQUIRED",
        "jdbc:mysql://localhost/southleaf_admin_it/?sslMode=REQUIRED",
        "jdbc:mysql://localhost/southleaf_admin%5fit?sslMode=REQUIRED"
      })
  void rejectsTargetAliases(String url) {
    assertThatThrownBy(() -> AdminRuntimePolicy.canonicalUrl("demo", "southleaf_admin_it", url))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresExplicitIsolatedModeAndSchema() {
    for (String mode : new String[] {null, "", "prod", "test"})
      assertThatThrownBy(() -> AdminRuntimePolicy.canonicalUrl(mode, "southleaf_admin_it", URL))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AdminRuntimePolicy.canonicalUrl("demo", "southleaf_admin", URL.replace("_it", "")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> AdminRuntimePolicy.canonicalUrl("production", "southleaf_admin_it", URL))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void supportedTlsAndBoundsAreExplicit() {
    for (String tls : new String[] {"REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY"})
      assertThat(
              AdminRuntimePolicy.canonicalUrl(
                  "demo",
                  "southleaf_admin_it",
                  URL.replace("REQUIRED", tls) + "&connectTimeout=1000&socketTimeout=15000"))
          .contains("sslMode=" + tls, "connectTimeout=1000", "socketTimeout=15000");
  }
}
