package cn.zhinan.adminruntime;

import cn.zhinan.adminsecurity.application.AdminLoginService;
import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.domain.AdminAuthenticationException;
import cn.zhinan.adminsecurity.domain.AdminPermission;
import cn.zhinan.adminsecurity.domain.AdminSession;
import cn.zhinan.adminsecurity.port.AdminSessionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Explicit real-isolated-MySQL probe, TEST SOURCE ONLY. Run by the authorized provisioning harness,
 * never packaged, auto-run against historical fixtures, or selected by a production environment
 * flag.
 */
public final class AdminRuntimeIsolatedProbe {
  private AdminRuntimeIsolatedProbe() {}

  public static void main(String[] args) throws Exception {
    var properties = AdminRuntimeProperties.load(System.getenv(), System.getProperties());
    if (args.length != 1) throw new IllegalArgumentException("Explicit probe required");
    if (args[0].equals("credential-login")) {
      String account = System.getenv("SOUTHLEAF_TEST_LOGIN_ACCOUNT");
      String secret = System.getenv("SOUTHLEAF_TEST_LOGIN_PASSWORD");
      require(account != null && secret != null && !secret.isBlank());
      try (var context = AdminRuntimeApplication.start(properties)) {
        require(context.getBeansOfType(DataSource.class).isEmpty());
        var login = context.getBean(AdminLoginService.class);
        var sessions = context.getBean(AdminSessionService.class);
        char[] supplied = secret.toCharArray();
        AdminSessionService.IssuedSession issued;
        try {
          issued =
              login.login(
                  account,
                  supplied,
                  UUID.fromString(System.getenv("SOUTHLEAF_TEST_LOGIN_SUCCESS_REQUEST")));
        } finally {
          Arrays.fill(supplied, '\0');
        }
        require(issued.token().matches("[A-Za-z0-9_-]{43}"));
        require(sessions.authenticate(issued.token()).accountId().equals(account));
        sessions.revoke(issued.token());
        String[][] attempts = {
          {account, "definitely-wrong", System.getenv("SOUTHLEAF_TEST_LOGIN_DENIED_REQUEST")},
          {"s1c2_absent", "synthetic", System.getenv("SOUTHLEAF_TEST_LOGIN_ABSENT_REQUEST")}
        };
        for (String[] attempt : attempts) {
          char[] value = attempt[1].toCharArray();
          try {
            login.login(attempt[0], value, UUID.fromString(attempt[2]));
            throw new AssertionError("Invalid credential accepted");
          } catch (AdminAuthenticationException expected) {
          } finally {
            Arrays.fill(value, '\0');
          }
        }
      }
      System.out.println("ADMIN_ISOLATED_CREDENTIAL_LOGIN_PASS");
    } else if (args[0].equals("store")) {
      try (var context = AdminRuntimeApplication.start(properties)) {
        require(context.getBeansOfType(DataSource.class).isEmpty());
        var store = context.getBean(AdminSessionStore.class);
        var issued = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String digest =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII)));
        var session =
            new AdminSession(digest, "s1b_fixture_account", 1, issued, issued.plusSeconds(3600));
        require(store.findCurrent(digest).isEmpty());
        require(store.createIfAccountCurrent(session));
        var current = store.findCurrent(digest).orElseThrow();
        require(current.session().equals(session));
        require(current.account().permissions().contains(AdminPermission.CONTENT_READ));
        store.revoke(digest, issued.plusSeconds(1));
        store.revoke(digest, issued.plusSeconds(2));
        require(store.findCurrent(digest).isEmpty());
      }
      System.out.println("ADMIN_ISOLATED_STORE_PASS");
    } else if (args[0].equals("unencrypted-connection")) {
      // A genuine plaintext JDBC connection enters the same verifier only via the test-source seam.
      // Production URL policy cannot select DISABLED and has no bypass switch.
      var source =
          new DriverManagerDataSource(
              properties
                  .url()
                  .replace("sslMode=VERIFY_IDENTITY", "sslMode=DISABLED")
                  .replace("allowPublicKeyRetrieval=false", "allowPublicKeyRetrieval=true"),
              properties.username(),
              properties.password());
      var closed = new AtomicBoolean();
      VerifiedAdminDataSource.Pool pool =
          new VerifiedAdminDataSource.Pool() {
            public DataSource source() {
              return source;
            }

            public void close() {
              closed.set(true);
            }
          };
      try {
        try (var unexpected = VerifiedAdminDataSource.open(properties, ignored -> pool)) {
          throw new AssertionError("Unencrypted real connection accepted");
        }
      } catch (AdminStartupException failure) {
        require(failure.reason() == AdminStartupException.Reason.TLS);
        require(closed.get());
      }
      System.out.println("ADMIN_ISOLATED_PLAINTEXT_REJECTED");
    } else {
      throw new IllegalArgumentException("Unknown isolated probe");
    }
  }

  private static void require(boolean condition) {
    if (!condition) throw new AssertionError("Isolated Store contract failed");
  }
}
