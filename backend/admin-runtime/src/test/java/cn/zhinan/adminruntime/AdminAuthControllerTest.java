package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.*;

import cn.zhinan.adminsecurity.application.AdminLoginService;
import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.domain.AdminAccount;
import cn.zhinan.adminsecurity.domain.AdminPermission;
import cn.zhinan.adminsecurity.domain.AdminSession;
import cn.zhinan.adminsecurity.domain.CredentialSnapshot;
import cn.zhinan.adminsecurity.domain.PasswordMatch;
import cn.zhinan.adminsecurity.port.AdminSecurityStorageException;
import cn.zhinan.adminsecurity.port.AdminSessionStore;
import cn.zhinan.adminsecurity.port.SessionTokenCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class AdminAuthControllerTest {
  private static final String TEST_TOKEN = "T".repeat(43);

  @Test
  void csrfEnvelopeIsNoStoreAndDoesNotAuthenticate() {
    var fixture = new Fixture();
    var token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "synthetic-token");
    var response = fixture.controller.csrf(token);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getHeaders().getFirst("X-Request-Id")).matches("[0-9a-f-]{36}");
    assertThat(response.getBody().data().csrfToken()).isEqualTo("synthetic-token");
    assertThat(fixture.verificationCalls).hasValue(0);
  }

  @Test
  void loginSessionAndLogoutUseOnlySecureCookiesAndCurrentPermissions() {
    var fixture = new Fixture();
    var login =
        jsonRequest(
            "POST",
            "/api/admin/v1/auth/login",
            "{\"accountId\":\"staff\",\"password\":\"correct\"}");
    var loginResponse = fixture.controller.login(login);

    assertThat(loginResponse.getStatusCode().value()).isEqualTo(200);
    assertThat(loginResponse.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(loginResponse.getHeaders().getFirst("X-Request-Id")).matches("[0-9a-f-]{36}");
    var loginBody = (AdminAuthController.IdentityEnvelope) loginResponse.getBody();
    assertThat(loginBody.data().accountId()).isEqualTo("staff");
    assertThat(loginBody.data().permissions()).containsExactly("CONTENT_READ", "ORDER_READ");
    assertThat(loginBody.data().expiresAt()).isEqualTo("2026-10-24T14:00:00Z");
    assertThat(loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE))
        .anySatisfy(
            cookie ->
                assertThat(cookie)
                    .startsWith(AdminAuthController.SESSION_COOKIE + "=")
                    .contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/")
                    .doesNotContain("Domain", "correct"));

    String sessionValue = fixture.sessionStore.lastSessionToken;
    var sessionRequest = new MockHttpServletRequest("GET", "/api/admin/v1/auth/session");
    sessionRequest.setSecure(true);
    sessionRequest.setCookies(
        new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, sessionValue));
    var sessionResponse = fixture.controller.session(sessionRequest);
    assertThat(sessionResponse.getStatusCode().value()).isEqualTo(200);
    assertThat(((AdminAuthController.IdentityEnvelope) sessionResponse.getBody()).data())
        .isEqualTo(loginBody.data());

    var logout = jsonRequest("POST", "/api/admin/v1/auth/logout", "{}");
    logout.setCookies(
        new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, sessionValue));
    var logoutResponse = fixture.controller.logout(logout);
    assertThat(logoutResponse.getStatusCode().value()).isEqualTo(204);
    assertThat(fixture.sessionStore.revoked).isTrue();
    assertThat(logoutResponse.getHeaders().get(HttpHeaders.SET_COOKIE))
        .anySatisfy(
            cookie ->
                assertThat(cookie)
                    .startsWith(AdminAuthController.SESSION_COOKIE + "=")
                    .contains("Max-Age=0", "Secure", "HttpOnly"))
        .anySatisfy(
            cookie ->
                assertThat(cookie)
                    .startsWith(AdminAuthController.CSRF_COOKIE + "=")
                    .contains("Max-Age=0", "Secure"));
  }

  @Test
  void strictLoginBodyRejectsUnknownDuplicateAndOversizedFieldsBeforeKdf() {
    var fixture = new Fixture();
    var unknown =
        jsonRequest(
            "POST",
            "/api/admin/v1/auth/login",
            "{\"accountId\":\"staff\",\"password\":\"correct\",\"extra\":1}");
    assertThat(fixture.controller.login(unknown).getStatusCode().value()).isEqualTo(400);

    var duplicate =
        jsonRequest(
            "POST",
            "/api/admin/v1/auth/login",
            "{\"accountId\":\"staff\",\"accountId\":\"other\",\"password\":\"correct\"}");
    assertThat(fixture.controller.login(duplicate).getStatusCode().value()).isEqualTo(400);

    var oversized = jsonRequest("POST", "/api/admin/v1/auth/login", "x".repeat(4097));
    assertThat(fixture.controller.login(oversized).getStatusCode().value()).isEqualTo(413);
    assertThat(fixture.verificationCalls).hasValue(0);
  }

  @Test
  void ambiguousSessionCookiesFailClosedBeforeCredentialVerification() {
    var fixture = new Fixture();
    var request =
        jsonRequest(
            "POST",
            "/api/admin/v1/auth/login",
            "{\"accountId\":\"staff\",\"password\":\"correct\"}");
    request.setCookies(
        new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, "first"),
        new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, "second"));

    var response = fixture.controller.login(request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(fixture.verificationCalls).hasValue(0);
  }

  @Test
  void invalidSessionIsClearedButStorageFailureDuringLogoutIsNotReportedAsSuccess() {
    var fixture = new Fixture();
    var noSession = new MockHttpServletRequest("GET", "/api/admin/v1/auth/session");
    noSession.setCookies(
        new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, "bad"));
    var missing = fixture.controller.session(noSession);
    assertThat(missing.getStatusCode().value()).isEqualTo(401);
    assertThat(missing.getHeaders().get(HttpHeaders.SET_COOKIE))
        .anySatisfy(cookie -> assertThat(cookie).contains("Max-Age=0", "HttpOnly", "Secure"));

    var login =
        jsonRequest(
            "POST",
            "/api/admin/v1/auth/login",
            "{\"accountId\":\"staff\",\"password\":\"correct\"}");
    fixture.controller.login(login);
    var logout = jsonRequest("POST", "/api/admin/v1/auth/logout", "{}");
    logout.setCookies(
        new jakarta.servlet.http.Cookie(
            AdminAuthController.SESSION_COOKIE, fixture.sessionStore.lastSessionToken));
    fixture.sessionStore.failRevoke = true;
    var failedLogout = fixture.controller.logout(logout);
    assertThat(failedLogout.getStatusCode().value()).isEqualTo(503);
    assertThat(failedLogout.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
  }

  private static MockHttpServletRequest jsonRequest(String method, String path, String body) {
    var request = new MockHttpServletRequest(method, path);
    request.setSecure(true);
    request.setContentType("application/json; charset=UTF-8");
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    return request;
  }

  private static final class Fixture {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC);
    private final Store sessionStore = new Store();
    private final AtomicInteger verificationCalls = new AtomicInteger();
    private final AdminSessionService sessions =
        new AdminSessionService(sessionStore, new TestTokenCodec(), clock, Duration.ofHours(2));
    private final AdminLoginService logins =
        new AdminLoginService(
            accountId ->
                accountId.equals("staff")
                    ? Optional.of(new CredentialSnapshot("staff", true, 0, "test-hash"))
                    : Optional.empty(),
            new cn.zhinan.adminsecurity.port.PasswordVerifier() {
              @Override
              public PasswordMatch verify(char[] password, String credentialHash) {
                verificationCalls.incrementAndGet();
                return new PasswordMatch("correct".equals(new String(password)), false);
              }

              @Override
              public void consumeDummyCost(char[] password) {
                verificationCalls.incrementAndGet();
              }
            },
            sessions,
            event -> {},
            clock);
    private final AdminAuthController controller =
        new AdminAuthController(
            logins,
            sessions,
            AdminWebProperties.load(webEnvironment()),
            new AdminLoginRateLimiter(clock),
            clock);

    private static Map<String, String> webEnvironment() {
      return Map.of(
          "SOUTHLEAF_ADMIN_WEB_MODE", "demo",
          "SOUTHLEAF_ADMIN_WEB_ORIGIN", "https://localhost:18443",
          "SOUTHLEAF_ADMIN_WEB_PORT", "18443",
          "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PATH", "C:/owned/test.jks",
          "SOUTHLEAF_ADMIN_WEB_KEYSTORE_PASSWORD", "FAKE_SECRET_SENTINEL",
          "SOUTHLEAF_ADMIN_WEB_KEYSTORE_TYPE", "JKS");
    }
  }

  private static final class Store implements AdminSessionStore {
    private final AdminAccount account =
        new AdminAccount(
            "staff", true, 0, Set.of(AdminPermission.ORDER_READ, AdminPermission.CONTENT_READ));
    private AdminSession session;
    private String lastSessionToken;
    private boolean revoked;
    private boolean failRevoke;

    @Override
    public boolean createIfAccountCurrent(AdminSession value) {
      if (!account.enabled() || account.securityVersion() != value.securityVersion()) return false;
      session = value;
      lastSessionToken = TEST_TOKEN;
      revoked = false;
      return true;
    }

    @Override
    public Optional<SessionState> findCurrent(String digest) {
      if (session == null || revoked || !session.tokenDigest().equals(digest))
        return Optional.empty();
      return Optional.of(new SessionState(session, account));
    }

    @Override
    public void revoke(String digest, Instant revokedAt) {
      if (failRevoke)
        throw new AdminSecurityStorageException(new IllegalStateException("synthetic"));
      if (session != null && session.tokenDigest().equals(digest)) revoked = true;
    }
  }

  private static final class TestTokenCodec implements SessionTokenCodec {
    @Override
    public String generate() {
      return TEST_TOKEN;
    }

    @Override
    public String digest(String token) {
      try {
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new AssertionError(impossible);
      }
    }
  }
}
