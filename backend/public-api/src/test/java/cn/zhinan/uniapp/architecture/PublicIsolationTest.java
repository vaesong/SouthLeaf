package cn.zhinan.uniapp.architecture;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PublicIsolationTest {
  @Test
  void publicClasspathHasNoManagementTypesOrResources() {
    var loader = getClass().getClassLoader();
    for (String name :
        new String[] {
          "cn.zhinan.uniapp.adminsecurity.application.AdminSessionService",
          "cn.zhinan.adminsecurity.application.AdminSessionService",
          "cn.zhinan.adminruntime.AdminRuntimeApplication",
          "cn.zhinan.adminmigration.AdminDatabaseMigrationMain"
        }) {
      assertThatThrownBy(() -> Class.forName(name, false, loader))
          .isInstanceOf(ClassNotFoundException.class);
    }
    assertThat(loader.getResource("application-admin.properties")).isNull();
    assertThat(loader.getResource("db/admin-migration/V2__admin_security.sql")).isNull();
  }
}
