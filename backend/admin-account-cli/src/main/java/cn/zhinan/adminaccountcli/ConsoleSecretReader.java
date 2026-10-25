package cn.zhinan.adminaccountcli;

import java.io.Console;
import java.util.Arrays;

/** Console only: redirected standard input is intentionally not an account-password channel. */
final class ConsoleSecretReader implements SecretReader {
  private final Console console;

  ConsoleSecretReader(Console console) {
    this.console = console;
  }

  @Override
  public char[] readTwice() {
    if (console == null) throw new AccountCliException(AccountCliException.Reason.SECURE_INPUT);
    char[] first = console.readPassword();
    char[] second = console.readPassword();
    if (first == null || second == null || !Arrays.equals(first, second)) {
      if (first != null) Arrays.fill(first, '\0');
      if (second != null) Arrays.fill(second, '\0');
      throw new AccountCliException(AccountCliException.Reason.SECURE_INPUT);
    }
    Arrays.fill(second, '\0');
    return first;
  }
}
