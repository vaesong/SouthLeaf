package cn.zhinan.uniapp.config;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeAccountVerifierTest {
  private final List<String> grants =
      List.of(
          "GRANT USAGE ON *.* TO `reader`@`127.0.0.1`",
          "GRANT SELECT ON `public_db`.* TO `reader`@`127.0.0.1`");

  private void verify(
      String user, String cipher, List<String> permissions, String mode, String url) {
    RuntimeAccountVerifier.validate("public_db", user, cipher, permissions, mode, url);
  }

  @Test
  void directSchemaReadOnlyAndTlsAreRequired() {
    assertThatCode(
            () ->
                verify(
                    "reader@127.0.0.1",
                    "TLS_AES_256_GCM_SHA384",
                    grants,
                    "demo",
                    "jdbc:mysql://127.0.0.1/public_db?sslMode=REQUIRED"))
        .doesNotThrowAnyException();
  }

  @Test
  void rootIsRejected() {
    assertThatThrownBy(
            () -> verify("root@localhost", "TLS", grants, "demo", "jdbc:mysql://host/db"))
        .hasMessageContaining("SELECT-only");
  }

  @Test
  void unencryptedConnectionsAreRejected() {
    assertThatThrownBy(() -> verify("reader@localhost", "", grants, "demo", "jdbc:mysql://host/db"))
        .hasMessageContaining("encrypted");
  }

  @Test
  void writesGlobalScopeRolesAndGrantOptionAreRejected() {
    for (String grant :
        List.of(
            "GRANT ALL PRIVILEGES ON `public_db`.* TO `writer`@`localhost`",
            "GRANT SELECT ON *.* TO `reader`@`localhost`",
            "GRANT `read_role` TO `reader`@`localhost`",
            "GRANT SELECT ON `public_db`.* TO `reader`@`localhost` WITH GRANT OPTION"))
      assertThatThrownBy(
              () ->
                  verify("reader@localhost", "TLS", List.of(grant), "demo", "jdbc:mysql://host/db"))
          .hasMessageContaining("SELECT-only");
  }

  @Test
  void productionRequiresIdentityCheckingNotEncryptionAlone() {
    assertThatThrownBy(
            () ->
                verify(
                    "reader@localhost",
                    "TLS",
                    grants,
                    "production",
                    "jdbc:mysql://host/db?sslMode=REQUIRED"))
        .hasMessageContaining("VERIFY_IDENTITY");
    assertThatCode(
            () ->
                verify(
                    "reader@localhost",
                    "TLS",
                    grants,
                    "production",
                    "jdbc:mysql://host/db?sslMode=VERIFY_IDENTITY"))
        .doesNotThrowAnyException();
  }
}
