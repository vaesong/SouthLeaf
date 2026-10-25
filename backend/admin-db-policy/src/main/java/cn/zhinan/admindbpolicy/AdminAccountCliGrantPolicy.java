package cn.zhinan.admindbpolicy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exact direct grants for the non-Web account lifecycle CLI, separate from runtime and migrator.
 */
public final class AdminAccountCliGrantPolicy {
  public static final String CONTRACT_ID = "admin-account-cli-v1";
  private static final Pattern ROW =
      Pattern.compile(
          "GRANT ([A-Z]+(?:, [A-Z]+)*) ON (\\*|`[A-Za-z0-9_]+`)\\.(\\*|`[A-Za-z0-9_]+`) TO `([A-Za-z][A-Za-z0-9_]{0,31})`@`([A-Za-z0-9._%:/-]{1,255})`");
  private static final Set<String> REQUIRED =
      Set.of(
          "admin_bootstrap_guard:SELECT",
          "admin_bootstrap_guard:UPDATE",
          "admin_account:SELECT",
          "admin_account:INSERT",
          "admin_account:UPDATE",
          "admin_account_permission:SELECT",
          "admin_account_permission:INSERT",
          "admin_account_permission:DELETE",
          "admin_account_command:SELECT",
          "admin_account_command:INSERT");

  private AdminAccountCliGrantPolicy() {}

  public static void validate(String schema, String expectedUser, List<String> grants) {
    if (schema == null
        || expectedUser == null
        || grants == null
        || grants.isEmpty()
        || grants.size() > 64) fail();
    var found = new HashSet<String>();
    boolean usage = false;
    for (String line : grants) {
      if (line == null || line.length() > 4096) fail();
      var match = ROW.matcher(line);
      if (!match.matches()) fail();
      if (!(match.group(4) + "@" + match.group(5)).equals(expectedUser)) fail();
      String privileges = match.group(1), db = match.group(2), table = match.group(3);
      if (db.equals("*") && table.equals("*") && privileges.equals("USAGE")) {
        if (usage) fail();
        usage = true;
        continue;
      }
      if (!db.equals("`" + schema + "`") || table.equals("*")) fail();
      String name = table.substring(1, table.length() - 1);
      for (String privilege : privileges.split(", ")) {
        String key = name + ":" + privilege;
        if (!REQUIRED.contains(key) || !found.add(key)) fail();
      }
    }
    if (!usage || !found.equals(REQUIRED)) fail();
  }

  private static void fail() {
    throw new IllegalArgumentException("Account CLI grants rejected");
  }
}
