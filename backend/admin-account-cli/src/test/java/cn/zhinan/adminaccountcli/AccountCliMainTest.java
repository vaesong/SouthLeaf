package cn.zhinan.adminaccountcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AccountCliMainTest {
  @Test
  void rejectsBadArgumentsBeforeLoadingConfigurationOrOpeningDatabase() {
    var bytes = new ByteArrayOutputStream();
    int exit =
        AccountCliMain.run(
            new String[] {"bootstrap", "--password", "do-not-pass-secrets"},
            Map.of(),
            () -> {
              throw new AssertionError("secret reader must not run");
            },
            new PrintStream(bytes, true, StandardCharsets.UTF_8));

    assertThat(exit).isEqualTo(2);
    assertThat(bytes.toString(StandardCharsets.UTF_8))
        .contains("reason=ARGUMENT")
        .doesNotContain("do-not-pass-secrets");
  }

  @Test
  void rejectsInvalidConfigurationBeforeRequestingAnAccountPassword() {
    var bytes = new ByteArrayOutputStream();
    int exit =
        AccountCliMain.run(
            new String[] {
              "bootstrap",
              "--command-id",
              "00000000-0000-0000-0000-000000000001",
              "--account",
              "ops.bootstrap",
              "--permission",
              "STAFF_ACCESS_MANAGE"
            },
            Map.of(),
            () -> {
              throw new AssertionError(
                  "secret reader must not run before configuration verification");
            },
            new PrintStream(bytes, true, StandardCharsets.UTF_8));

    assertThat(exit).isEqualTo(2);
    assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("reason=CONFIGURATION");
  }

  @Test
  void standaloneProcessRejectsSecretFlagsWithoutAnyDatabaseConfiguration() throws Exception {
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
            .toString();
    Process process =
        new ProcessBuilder(
                executable,
                "-cp",
                System.getProperty("java.class.path"),
                AccountCliMain.class.getName(),
                "bootstrap",
                "--command-id",
                "00000000-0000-0000-0000-000000000001",
                "--password",
                "never-a-password-channel")
            .redirectErrorStream(true)
            .start();

    assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.exitValue()).isEqualTo(2);
    assertThat(output).contains("reason=ARGUMENT").doesNotContain("never-a-password-channel");
  }
}
