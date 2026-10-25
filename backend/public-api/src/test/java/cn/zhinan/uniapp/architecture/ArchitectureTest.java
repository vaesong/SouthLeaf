package cn.zhinan.uniapp.architecture;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private final Path sources = Path.of("src/main/java/cn/zhinan/uniapp");

  @Test
  void applicationDomainAndPortsDoNotDependOnAdaptersOrStorageApis() throws Exception {
    try (var files = Files.walk(sources)) {
      for (Path p : files.filter(x -> x.toString().endsWith(".java")).toList()) {
        String name = p.toString().replace('\\', '/');
        if (!name.contains("/application/")
            && !name.contains("/domain/")
            && !name.contains("/port/")) continue;
        assertThat(Files.readString(p))
            .as(name)
            .doesNotContain(
                ".adapter.",
                "org.springframework.",
                "jakarta.servlet.",
                "jakarta.persistence.",
                "java.sql.",
                "java.nio.file",
                "ObjectMapper");
      }
    }
  }

  @Test
  void noLegacyOrFallbackImplementationsRemain() throws Exception {
    try (var files = Files.walk(sources)) {
      assertThat(files.map(p -> p.getFileName().toString()).toList())
          .noneMatch(
              n ->
                  n.startsWith("JsonPublic")
                      || n.startsWith("EmptyPublic")
                      || n.equals("PublicCatalogSource.java")
                      || n.equals("LegacyLocalConfiguration.java")
                      || n.equals("CategoryController.java")
                      || n.equals("PackageController.java"));
    }
    assertThat(Files.exists(Path.of("src/main/resources/public-data/catalog.json"))).isFalse();
    assertThat(Files.exists(Path.of("src/main/resources/application-legacy-local.properties")))
        .isFalse();
  }

  @Test
  void runtimeMigrationsAndSecretsAreNotImplicit() throws Exception {
    String properties = Files.readString(Path.of("src/main/resources/application.properties"));
    assertThat(properties)
        .contains(
            "spring.flyway.enabled=false",
            "spring.sql.init.mode=never",
            "${SOUTHLEAF_DB_PASSWORD}",
            "logs/application.%d{yyyy-MM-dd}.%i.log")
        .doesNotContain("ddl-auto", "static-locations", "spring.autoconfigure.exclude");
  }
}
