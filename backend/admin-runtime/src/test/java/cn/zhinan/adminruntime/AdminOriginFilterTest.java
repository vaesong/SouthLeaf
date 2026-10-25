package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminOriginFilterTest {
  private static AdminWebProperties properties() {
    return AdminWebProperties.load(
        Map.of(
            "SOUTHLEAF_ADMIN_WEB_MODE", "demo",
            "SOUTHLEAF_ADMIN_WEB_ORIGIN", "https://localhost:18443",
            "SOUTHLEAF_ADMIN_WEB_PORT", "18443",
            "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH", "C:/owned/test.jks",
            "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PASSWORD", "FAKE_SECRET_SENTINEL",
            "SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE", "JKS"));
  }

  @Test
  void rejectsMissingOrWrongOriginBeforeTheSecurityChain() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/admin/v1/auth/login");
    request.setSecure(true);
    request.setContentType("application/json");
    request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    new AdminOriginFilter(properties().originPolicy()).doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentAsString()).contains("ORIGIN_REJECTED", "requestId");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void rejectsOversizedWriteBeforeControllerOrCsrfParsing() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/admin/v1/auth/login");
    request.setSecure(true);
    request.addHeader("Origin", "https://localhost:18443");
    request.setContentType("application/json");
    request.setContent(new byte[4097]);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    new AdminOriginFilter(properties().originPolicy()).doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    assertThat(chain.getRequest()).isNull();
  }
}
