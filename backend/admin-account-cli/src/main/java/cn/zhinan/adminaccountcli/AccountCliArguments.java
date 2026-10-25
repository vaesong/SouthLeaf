package cn.zhinan.adminaccountcli;

import cn.zhinan.adminsecurity.domain.AdminPermission;
import java.util.*;

/**
 * Strict allowlist parser: no password option, no positional secrets, no unknown or duplicate
 * flags.
 */
final class AccountCliArguments {
  enum Action {
    BOOTSTRAP,
    CHANGE_PASSWORD,
    DISABLE_ACCOUNT,
    REPLACE_PERMISSIONS,
    COMMAND_RESULT
  }

  record Parsed(
      Action action,
      UUID commandId,
      String accountId,
      Long expectedVersion,
      Set<AdminPermission> permissions) {}

  private AccountCliArguments() {}

  static Parsed parse(String[] args) {
    if (args == null || args.length < 3) fail();
    Action action = action(args[0]);
    var values = new LinkedHashMap<String, List<String>>();
    for (int i = 1; i < args.length; i += 2) {
      if (i + 1 >= args.length || !args[i].startsWith("--") || args[i + 1].startsWith("--")) fail();
      String key = args[i];
      if (key.equals("--password") || key.equals("--secret")) fail();
      values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(args[i + 1]);
    }
    UUID id = uuid(single(values, "--command-id"));
    return switch (action) {
      case COMMAND_RESULT -> {
        requireOnly(values, Set.of("--command-id"));
        yield new Parsed(action, id, null, null, Set.of());
      }
      case BOOTSTRAP ->
          new Parsed(
              action,
              id,
              account(values),
              null,
              permissions(values, Set.of("--command-id", "--account", "--permission")));
      case CHANGE_PASSWORD ->
          new Parsed(
              action,
              id,
              account(values),
              version(values),
              permissions(values, Set.of("--command-id", "--account", "--expected-version")));
      case DISABLE_ACCOUNT ->
          new Parsed(
              action,
              id,
              account(values),
              version(values),
              permissions(values, Set.of("--command-id", "--account", "--expected-version")));
      case REPLACE_PERMISSIONS ->
          new Parsed(
              action,
              id,
              account(values),
              version(values),
              permissions(
                  values,
                  Set.of("--command-id", "--account", "--expected-version", "--permission")));
    };
  }

  private static Action action(String value) {
    return switch (value) {
      case "bootstrap" -> Action.BOOTSTRAP;
      case "change-password" -> Action.CHANGE_PASSWORD;
      case "disable-account" -> Action.DISABLE_ACCOUNT;
      case "replace-permissions" -> Action.REPLACE_PERMISSIONS;
      case "command-result" -> Action.COMMAND_RESULT;
      default -> throw reject();
    };
  }

  private static String account(Map<String, List<String>> values) {
    return single(values, "--account");
  }

  private static Long version(Map<String, List<String>> values) {
    try {
      String value = single(values, "--expected-version");
      if (!value.matches("[0-9]{1,18}")) fail();
      long version = Long.parseLong(value);
      if (version < 0) fail();
      return version;
    } catch (NumberFormatException ex) {
      throw reject();
    }
  }

  private static Set<AdminPermission> permissions(
      Map<String, List<String>> values, Set<String> allowed) {
    requireOnly(values, allowed);
    var out = EnumSet.noneOf(AdminPermission.class);
    for (String text : values.getOrDefault("--permission", List.of())) {
      try {
        if (!out.add(AdminPermission.valueOf(text))) fail();
      } catch (IllegalArgumentException ex) {
        throw reject();
      }
    }
    if (allowed.contains("--permission") && out.isEmpty()) fail();
    return Set.copyOf(out);
  }

  private static String single(Map<String, List<String>> values, String key) {
    var list = values.get(key);
    if (list == null || list.size() != 1 || list.getFirst().isBlank()) fail();
    return list.getFirst();
  }

  private static UUID uuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ex) {
      throw reject();
    }
  }

  private static void requireOnly(Map<String, List<String>> values, Set<String> allowed) {
    if (!allowed.containsAll(values.keySet())) fail();
  }

  private static void fail() {
    throw reject();
  }

  private static AccountCliException reject() {
    return new AccountCliException(AccountCliException.Reason.ARGUMENT);
  }
}
