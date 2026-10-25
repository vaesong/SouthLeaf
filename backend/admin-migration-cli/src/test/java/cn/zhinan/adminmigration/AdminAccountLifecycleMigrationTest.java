package cn.zhinan.adminmigration;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AdminAccountLifecycleMigrationTest {
  @Test
  void v4AddsOnlyGuardAndNonSecretCommandFacts() throws IOException {
    String sql;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/admin-migration/V4__admin_account_lifecycle.sql")) {
      assertThat(stream).isNotNull();
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql)
        .contains("CREATE TABLE admin_account_command", "CREATE TABLE admin_bootstrap_guard")
        .contains("INSERT INTO admin_bootstrap_guard")
        .doesNotContain("credential_hash", "token_digest", "password_hash", "admin_session");
    assertThat(sql).doesNotContain("V1__", "V2__", "V3__");
  }
}
