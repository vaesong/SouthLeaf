package cn.zhinan.admindbpolicy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact minimal grants for the outbox dispatcher and operator recovery surface. */
public final class AdminOutboxGrantPolicy {
  private static final Pattern ROW =
      Pattern.compile(
          "GRANT ([A-Z]+(?:, [A-Z]+)*) ON (\\*|`[A-Za-z0-9_]+`)\\.(\\*|`[A-Za-z0-9_]+`) TO `([A-Za-z][A-Za-z0-9_]{0,31})`@`([A-Za-z0-9._%:/-]{1,255})`");
  private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,63}");
  private static final Set<String> REQUIRED =
      Set.of(
          "booking_notification_outbox:SELECT",
          "booking_notification_outbox:UPDATE",
          "booking_command:SELECT",
          "booking_command:INSERT",
          "booking_audit:INSERT");

  private AdminOutboxGrantPolicy() {}

  public static Set<String> required(String schema) {
    if (schema == null || !SCHEMA.matcher(schema).matches())
      throw new IllegalArgumentException("Outbox schema rejected");
    return REQUIRED.stream()
        .map(item -> schema + ":" + item)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public static void validate(String schema, String expectedUser, List<String> grants) {
    if (schema == null
        || !SCHEMA.matcher(schema).matches()
        || expectedUser == null
        || !expectedUser.matches("[A-Za-z][A-Za-z0-9_]{0,31}@[A-Za-z0-9._%:/-]{1,255}")
        || grants == null
        || grants.isEmpty()
        || grants.size() > 16) fail();
    Set<String> required = required(schema);
    var found = new HashSet<String>();
    boolean usage = false;
    for (String line : grants) {
      if (line == null || line.length() > 4096) fail();
      var match = ROW.matcher(line);
      if (!match.matches() || !(match.group(4) + "@" + match.group(5)).equals(expectedUser)) fail();
      String database = match.group(2);
      String table = match.group(3);
      String privileges = match.group(1);
      if (database.equals("*") && table.equals("*") && privileges.equals("USAGE")) {
        if (usage) fail();
        usage = true;
        continue;
      }
      if (!database.equals("`" + schema + "`") || table.equals("*")) fail();
      String name = table.substring(1, table.length() - 1);
      for (String privilege : privileges.split(", "))
        if (!required.contains(schema + ":" + name + ":" + privilege)
            || !found.add(schema + ":" + name + ":" + privilege)) fail();
    }
    if (!usage || !found.equals(required)) fail();
  }

  private static void fail() {
    throw new IllegalArgumentException("Outbox writer grants rejected");
  }
}
