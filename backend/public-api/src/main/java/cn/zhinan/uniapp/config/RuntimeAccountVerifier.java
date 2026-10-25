package cn.zhinan.uniapp.config;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Startup policy, never a repair or privilege-elevation tool. */
@Component
public class RuntimeAccountVerifier {
  private final DataSource source;
  private final String mode;

  public RuntimeAccountVerifier(DataSource source, @Value("${southleaf.public.mode}") String mode) {
    this.source = source;
    this.mode = mode;
  }

  public void verify() {
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      String schema, user, cipher = "";
      List<String> grants = new ArrayList<>();
      try (var rows = statement.executeQuery("SELECT DATABASE(),CURRENT_USER()")) {
        rows.next();
        schema = rows.getString(1);
        user = rows.getString(2);
      }
      try (var rows = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
        if (rows.next()) cipher = rows.getString(2);
      }
      try (var rows = statement.executeQuery("SHOW GRANTS")) {
        while (rows.next()) grants.add(rows.getString(1));
      }
      validate(schema, user, cipher, grants, mode, connection.getMetaData().getURL());
    } catch (SQLException ex) {
      throw new IllegalStateException("Runtime database identity verification failed", ex);
    }
  }

  static void validate(
      String schema, String user, String cipher, List<String> grants, String mode, String url) {
    if (schema == null
        || schema.isBlank()
        || user == null
        || user.toLowerCase(Locale.ROOT).startsWith("root@"))
      throw new IllegalStateException("Runtime requires a dedicated SELECT-only account");
    if (cipher == null || cipher.isBlank())
      throw new IllegalStateException("Runtime requires an encrypted database connection");
    if ("production".equals(mode)
        && !Pattern.compile("(?:[?&])sslMode=VERIFY_IDENTITY(?:&|$)", Pattern.CASE_INSENSITIVE)
            .matcher(url)
            .find()) throw new IllegalStateException("Production requires sslMode=VERIFY_IDENTITY");
    boolean select = false;
    String selectPattern = "(?i)^GRANT SELECT ON `?" + Pattern.quote(schema) + "`?\\.\\* TO .+$";
    for (String grant : grants) {
      String normalized = grant.replaceAll("\\s+", " ");
      if (normalized.toUpperCase(Locale.ROOT).contains("WITH GRANT OPTION"))
        throw new IllegalStateException("Runtime requires direct SELECT-only grants on its schema");
      if (normalized.matches(selectPattern)) {
        select = true;
        continue;
      }
      if (normalized.matches("(?i)^GRANT USAGE ON \\*\\.\\* TO .+$")) continue;
      throw new IllegalStateException(
          "Runtime requires direct SELECT-only grants on its schema; roles and broader grants are not accepted");
    }
    if (!select) throw new IllegalStateException("Runtime schema SELECT grant is missing");
  }
}
