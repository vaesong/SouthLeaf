package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdminRuntimeBoundaryTest {
  @Test
  void rejectsUnsupportedArgumentsWithoutStarting() {
    assertThat(
            AdminRuntimeApplication.run(
                new String[] {"--server.port=0"}, java.util.Map.of(), new java.util.Properties()))
        .isEqualTo(2);
  }

  @Test
  void runtimeHasNoPublicOrMigrationClasspath() {
    var loader = getClass().getClassLoader();
    for (String name :
        new String[] {
          "cn.zhinan.uniapp.UniappApiApplication",
          "cn.zhinan.uniapp.config.PublicRuntimeConfiguration",
          "cn.zhinan.adminmigration.AdminDatabaseMigrationMain",
          "org.flywaydb.core.Flyway"
        }) {
      assertThatThrownBy(() -> Class.forName(name, false, loader))
          .isInstanceOf(ClassNotFoundException.class);
    }
    for (String resource :
        new String[] {
          "application.properties",
          "db/migration/V1__public_catalog.sql",
          "db/admin-migration/V2__admin_security.sql",
          "db/demo/seed.sql"
        }) {
      assertThat(loader.getResource(resource)).as(resource).isNull();
    }
    assertThat(loader.getResource("application-admin.properties")).isNotNull();
  }
}
