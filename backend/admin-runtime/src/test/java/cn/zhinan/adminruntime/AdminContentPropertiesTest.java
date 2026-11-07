package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminContentPropertiesTest {
  @TempDir Path root;

  @Test
  void fileAndCosAreMutuallyExclusiveAndDemoCannotUseTheProductionSchema() {
    var file =
        AdminContentProperties.load(
            fileEnv(root), "demo", "southleaf_admin", "southleaf_runtime", true);
    assertThat(file.mediaStore()).isEqualTo("file");
    assertThat(file.mediaRoot()).isEqualTo(root);
    assertThat(file.cosSecretKey()).isNull();

    Map<String, String> mixed = fileEnv(root);
    mixed.put("SOUTHLEAF_ADMIN_CONTENT_COS_SECRET_ID", "AKIDtestexample00000000000000000000");
    assertThatThrownBy(
            () ->
                AdminContentProperties.load(
                    mixed, "demo", "southleaf_admin", "southleaf_runtime", true))
        .isInstanceOf(AdminStartupException.class);

    Map<String, String> demoOnProduction = fileEnv(root);
    demoOnProduction.put("SOUTHLEAF_ADMIN_CONTENT_PUBLIC_SCHEMA", "southleaf_public");
    assertThatThrownBy(
            () ->
                AdminContentProperties.load(
                    demoOnProduction, "demo", "southleaf_admin", "southleaf_runtime", true))
        .isInstanceOf(AdminStartupException.class);

    var cos =
        AdminContentProperties.load(
            cosEnv(), "production", "southleaf_admin", "southleaf_runtime", true);
    assertThat(cos.mediaStore()).isEqualTo("cos");
    assertThat(cos.mediaRoot()).isNull();
    assertThat(cos.publicSchema()).isEqualTo("southleaf_public");
    assertThat(cos.toString()).doesNotContain("test-secret-key");
  }

  private static Map<String, String> fileEnv(Path root) {
    Map<String, String> env = base("southleaf_public_demo");
    env.put("SOUTHLEAF_ADMIN_CONTENT_MEDIA_STORE", "file");
    env.put("SOUTHLEAF_ADMIN_CONTENT_MEDIA_ROOT", root.toString());
    return env;
  }

  private static Map<String, String> cosEnv() {
    Map<String, String> env = base("southleaf_public");
    env.put("SOUTHLEAF_ADMIN_CONTENT_MEDIA_STORE", "cos");
    env.put("SOUTHLEAF_ADMIN_CONTENT_COS_SECRET_ID", "AKIDtestexample00000000000000000000");
    env.put("SOUTHLEAF_ADMIN_CONTENT_COS_SECRET_KEY", "test-secret-key");
    env.put("SOUTHLEAF_ADMIN_CONTENT_COS_BUCKET", "examplebucket-1250000000");
    env.put("SOUTHLEAF_ADMIN_CONTENT_COS_REGION", "ap-guangzhou");
    return env;
  }

  private static Map<String, String> base(String schema) {
    Map<String, String> env = new HashMap<>();
    env.put("SOUTHLEAF_ADMIN_CONTENT_MODE", "enabled");
    env.put("SOUTHLEAF_ADMIN_CONTENT_BUSINESS_DB_USERNAME", "southleaf_content");
    env.put("SOUTHLEAF_ADMIN_CONTENT_BUSINESS_DB_PASSWORD", "business-password");
    env.put("SOUTHLEAF_ADMIN_CONTENT_BUSINESS_DB_EXPECTED_USER", "southleaf_content@localhost");
    env.put("SOUTHLEAF_ADMIN_CONTENT_PUBLICATION_DB_USERNAME", "southleaf_publisher");
    env.put("SOUTHLEAF_ADMIN_CONTENT_PUBLICATION_DB_PASSWORD", "publication-password");
    env.put(
        "SOUTHLEAF_ADMIN_CONTENT_PUBLICATION_DB_EXPECTED_USER", "southleaf_publisher@localhost");
    env.put("SOUTHLEAF_ADMIN_CONTENT_PUBLIC_SCHEMA", schema);
    return env;
  }
}
