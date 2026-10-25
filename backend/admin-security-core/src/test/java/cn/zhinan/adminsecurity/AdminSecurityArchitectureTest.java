package cn.zhinan.adminsecurity;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Layer rules, complemented by reactor graph, jdeps and final artifact checks. */
class AdminSecurityArchitectureTest {
  private final Path main = Path.of("src/main/java/cn/zhinan/adminsecurity");
  private final Pattern imports = Pattern.compile("(?m)^import (?:static )?([\\w.]+)(?:\\.\\*)?;");

  @Test
  void coreImportsOnlyJdkAndItsOwnInwardLayers() throws Exception {
    for (String layer : Set.of("domain", "port", "application")) {
      try (var paths = Files.walk(main.resolve(layer))) {
        for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
          var matcher = imports.matcher(Files.readString(path));
          while (matcher.find()) {
            String dependency = matcher.group(1);
            boolean allowed =
                dependency.startsWith("java.")
                    || dependency.startsWith("cn.zhinan.adminsecurity.domain.")
                    || (!layer.equals("domain")
                        && dependency.startsWith("cn.zhinan.adminsecurity.port."));
            assertThat(allowed).as("%s imports %s", path, dependency).isTrue();
          }
        }
      }
    }
  }

  @Test
  void coreHasNoProductionAdaptersOrFrameworkAnnotations() throws Exception {
    try (var paths = Files.walk(main)) {
      for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
        assertThat(Files.readString(path))
            .doesNotContain(
                "org.springframework.",
                "jakarta.servlet.",
                "class MemoryStore",
                "class TestSessionTokenCodec",
                ".adapter.");
      }
    }
  }

  @Test
  void coreDoesNotOwnRuntimeResources() {
    assertThat(Files.exists(Path.of("src/main/resources/application.properties"))).isFalse();
    assertThat(Files.exists(Path.of("src/main/resources/db"))).isFalse();
  }
}
