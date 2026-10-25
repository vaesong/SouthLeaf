package cn.zhinan.adminsecurity;

import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Test only: the owner harness supplies a verified independent instance, never a Reader fallback.
 */
final class AdminDatabaseTestSupport {
  private AdminDatabaseTestSupport() {}

  static DataSource connect() {
    String url = required("SOUTHLEAF_SECURITY_IT_URL");
    var match =
        Pattern.compile(
                "jdbc:mysql://localhost:([0-9]{1,5})/southleaf_admin_s1b_it[?]sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")
            .matcher(url);
    if (!match.matches())
      throw new IllegalStateException("Explicit isolated management test target required");
    int port = Integer.parseInt(match.group(1));
    if (port <= 0 || port > 65535 || port == 3306)
      throw new IllegalStateException("Original server is not a management test target");
    System.setProperty("javax.net.ssl.trustStore", required("SOUTHLEAF_SECURITY_IT_TRUSTSTORE"));
    System.setProperty("javax.net.ssl.trustStoreType", "JKS");
    var source =
        new DriverManagerDataSource(
            url,
            required("SOUTHLEAF_SECURITY_IT_USERNAME"),
            required("SOUTHLEAF_SECURITY_IT_PASSWORD"));
    var jdbc = new JdbcTemplate(source);
    String expected = required("SOUTHLEAF_SECURITY_IT_UUID");
    String expectedUser = required("SOUTHLEAF_SECURITY_IT_EXPECTED_USER");
    if (!expected.equals(jdbc.queryForObject("SELECT @@server_uuid", String.class))
        || !expectedUser.equals(jdbc.queryForObject("SELECT CURRENT_USER()", String.class))
        || !"southleaf_admin_s1b_it".equals(jdbc.queryForObject("SELECT DATABASE()", String.class)))
      throw new IllegalStateException("Isolated management test identity mismatch");
    return source;
  }

  private static String required(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Required integration environment: " + key);
    return value;
  }
}
