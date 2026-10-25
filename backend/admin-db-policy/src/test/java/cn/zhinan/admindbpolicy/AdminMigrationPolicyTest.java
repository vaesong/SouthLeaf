package cn.zhinan.admindbpolicy;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdminMigrationPolicyTest {
  @Test
  void extractedPolicyKeepsMigrationTlsStrict() {
    assertThatCode(
            () ->
                AdminMigrationPolicy.validateMigrationUrl(
                    "jdbc:mysql://host/southleaf_admin?sslMode=VERIFY_IDENTITY"))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                AdminMigrationPolicy.validateMigrationUrl(
                    "jdbc:mysql://host/southleaf_admin?sslMode=REQUIRED"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactTargetStillRejectsPublicAndRoot() {
    assertThatThrownBy(
            () ->
                AdminMigrationPolicy.validateMigrationTarget(
                    "southleaf_public", "southleaf_public", "migrator@host", "TLS"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                AdminMigrationPolicy.validateMigrationTarget(
                    "southleaf_admin", "southleaf_admin", "root@host", "TLS"))
        .isInstanceOf(IllegalStateException.class);
  }
}
