package cn.zhinan.adminaccountcli;

import cn.zhinan.admincredentialcrypto.Argon2PasswordService;
import cn.zhinan.adminsecurity.application.AdminAccountLifecycleService;
import cn.zhinan.adminsecurity.domain.AccountLifecycleException;
import cn.zhinan.adminsecurity.domain.AccountLifecycleResult;
import cn.zhinan.adminsecurity.domain.AccountPasswordPolicy;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Dedicated, non-Web entry point for C3 lifecycle commands. */
public final class AccountCliMain {
  private AccountCliMain() {}

  public static void main(String[] args) {
    System.exit(run(args, System.getenv(), new ConsoleSecretReader(System.console()), System.out));
  }

  static int run(
      String[] args,
      Map<String, String> environment,
      SecretReader secretReader,
      PrintStream output) {
    Objects.requireNonNull(environment);
    Objects.requireNonNull(secretReader);
    Objects.requireNonNull(output);
    try {
      AccountCliArguments.Parsed parsed = AccountCliArguments.parse(args);
      AccountCliProperties properties = AccountCliProperties.load(environment);
      try (AccountCliDatabaseVerifier.VerifiedDatabase database =
          AccountCliDatabaseVerifier.open(properties)) {
        var lifecycle =
            new AdminAccountLifecycleService(
                new JdbcAccountLifecycleStore(database.dataSource(), database.executionIdentity()),
                new Argon2PasswordService(),
                new AccountPasswordPolicy());
        AccountLifecycleResult result = execute(parsed, lifecycle, secretReader);
        output.println(
            "ACCOUNT_CLI_SUCCESS commandId="
                + result.commandId()
                + " operation="
                + result.operation()
                + " account="
                + result.accountId()
                + " securityVersion="
                + result.securityVersion());
        return 0;
      }
    } catch (AccountCliException ex) {
      return rejected(output, ex.reason().name());
    } catch (AccountLifecycleException ex) {
      return rejected(output, ex.reason().name());
    } catch (RuntimeException ex) {
      return rejected(output, AccountCliException.Reason.DEPENDENCY.name());
    }
  }

  private static AccountLifecycleResult execute(
      AccountCliArguments.Parsed parsed,
      AdminAccountLifecycleService lifecycle,
      SecretReader secretReader) {
    return switch (parsed.action()) {
      case BOOTSTRAP ->
          withSecret(
              secretReader,
              password ->
                  lifecycle.bootstrap(
                      parsed.commandId(), parsed.accountId(), password, parsed.permissions()));
      case CHANGE_PASSWORD ->
          withSecret(
              secretReader,
              password ->
                  lifecycle.changePassword(
                      parsed.commandId(), parsed.accountId(), parsed.expectedVersion(), password));
      case DISABLE_ACCOUNT ->
          lifecycle.disableAccount(
              parsed.commandId(), parsed.accountId(), parsed.expectedVersion());
      case REPLACE_PERMISSIONS ->
          lifecycle.replacePermissions(
              parsed.commandId(),
              parsed.accountId(),
              parsed.expectedVersion(),
              parsed.permissions());
      case COMMAND_RESULT -> lifecycle.commandResult(parsed.commandId());
    };
  }

  private static AccountLifecycleResult withSecret(SecretReader reader, SecretOperation operation) {
    char[] password = reader.readTwice();
    try {
      return operation.run(password);
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  private static int rejected(PrintStream output, String reason) {
    output.println("ACCOUNT_CLI_REJECTED reason=" + reason);
    return 2;
  }

  @FunctionalInterface
  private interface SecretOperation {
    AccountLifecycleResult run(char[] password);
  }
}
