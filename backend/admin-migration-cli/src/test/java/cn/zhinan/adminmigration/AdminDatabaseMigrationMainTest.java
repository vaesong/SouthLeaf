package cn.zhinan.adminmigration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminDatabaseMigrationMainTest {
  @Test
  void requiresAnExplicitNonDestructiveAction() {
    assertThat(AdminDatabaseMigrationMain.action(new String[] {"--validate"}))
        .isEqualTo("--validate");
    assertThat(AdminDatabaseMigrationMain.action(new String[] {"--migrate"}))
        .isEqualTo("--migrate");
    assertThatThrownBy(() -> AdminDatabaseMigrationMain.action(new String[0]))
        .isInstanceOf(IllegalArgumentException.class);
    for (String action : new String[] {"--clean", "--repair", "--seed-demo", "--baseline"}) {
      assertThatThrownBy(() -> AdminDatabaseMigrationMain.action(new String[] {action}))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "southleaf_public",
        "southleaf_public_it",
        "mysql",
        "southleaf_admin_public;DROP",
        "other_admin"
      })
  void refusesPublicAndSystemSchemas(String schema) {
    assertThatThrownBy(
            () ->
                AdminDatabaseMigrationMain.validateTarget(
                    schema, schema, "migrator@host", "TLS_AES_256_GCM_SHA384"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void requiresExactSchemaDedicatedUserAndTls() {
    assertThatCode(
            () ->
                AdminDatabaseMigrationMain.validateTarget(
                    "southleaf_admin", "southleaf_admin", "migrator@host", "TLS"))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                AdminDatabaseMigrationMain.validateTarget(
                    "southleaf_admin", "southleaf_admin_other", "migrator@host", "TLS"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                AdminDatabaseMigrationMain.validateTarget(
                    "southleaf_admin", "southleaf_admin", "root@host", "TLS"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                AdminDatabaseMigrationMain.validateTarget(
                    "southleaf_admin", "southleaf_admin", "migrator@host", ""))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:mysql://host/southleaf_admin?sslMode=REQUIRED",
        "jdbc:mysql://host/southleaf_admin?sslMode=VERIFY_IDENTITY&sslMode=DISABLED",
        "jdbc:mysql://host/southleaf_admin?sslMode=VERIFY_IDENTITY&password=fixture",
        "jdbc:mysql://host/southleaf_admin?sslMode=VERIFY_IDENTITY&user=fixture",
        "jdbc:mysql://fixture@host/southleaf_admin?sslMode=VERIFY_IDENTITY"
      })
  void refusesWeakOrCredentialBearingUrls(String url) {
    assertThatThrownBy(() -> AdminDatabaseMigrationMain.validateUrl(url))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsEncodedAndAlternateCredentialProperties() {
    for (String property :
        new String[] {
          "ssl%4Dode=DISABLED",
          "%75ser=fixture",
          "clientCertificateKeyStorePassword=fixture",
          "trustCertificateKeyStorePassword=fixture",
          "customOption=fixture"
        }) {
      assertThatThrownBy(
              () ->
                  AdminDatabaseMigrationMain.validateUrl(
                      "jdbc:mysql://host/southleaf_admin?sslMode=VERIFY_IDENTITY&" + property))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void acceptsVerifiedTlsAndExternalCredentialsOnly() {
    assertThatCode(
            () ->
                AdminDatabaseMigrationMain.validateUrl(
                    "jdbc:mysql://host/southleaf_admin?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC"))
        .doesNotThrowAnyException();
  }
}
