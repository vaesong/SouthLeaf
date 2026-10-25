package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminWebUiControllerTest {
  private final AdminWebUiController controller = new AdminWebUiController();

  @Test
  void servesOnlyTheSpaEntryAndAssetsAndNeverSwallowsApiPaths() {
    var root = controller.root();
    assertThat(root.getStatusCode().value()).isEqualTo(200);
    assertThat(root.getBody()).isNotNull();
    assertThat(root.getBody().getFilename()).isEqualTo("index.html");

    var route = controller.route("/login");
    assertThat(route.getStatusCode().value()).isEqualTo(200);
    assertThat(route.getBody()).isNotNull();
    assertThat(((org.springframework.core.io.Resource) route.getBody()).getFilename())
        .isEqualTo("index.html");

    assertThat(controller.route("/api/admin/v1/auth/session").getStatusCode().value())
        .isEqualTo(404);
    assertThat(controller.route("/assets/../application-admin.properties").getStatusCode().value())
        .isEqualTo(404);
  }
}
