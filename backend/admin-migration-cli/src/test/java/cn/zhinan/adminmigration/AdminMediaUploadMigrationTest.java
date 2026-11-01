package cn.zhinan.adminmigration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AdminMediaUploadMigrationTest {
  @Test
  void v14RelaxesOnlyTheSourceSizeAndAddsANullableFileName() throws IOException {
    String v5 = resource("db/admin-migration/V5__admin_work_publication.sql");
    String v14 = resource("db/admin-migration/V14__admin_media_upload_limits.sql");

    assertThat(v5)
        .contains("source_byte_length BETWEEN 1 AND 4194304")
        .contains("byte_length BETWEEN 1 AND 4194304")
        .doesNotContain("source_file_name");
    assertThat(v14)
        .contains("DROP CHECK ck_admin_media_size")
        .contains("source_byte_length BETWEEN 1 AND 31457280")
        .contains("source_file_name VARCHAR(120) NULL")
        .doesNotContain("4194304")
        .doesNotContain("ck_admin_media_variant_size")
        .doesNotContain("admin_media_variant");
  }

  private static String resource(String path) throws IOException {
    try (var stream =
        AdminMediaUploadMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
