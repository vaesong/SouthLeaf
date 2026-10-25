package cn.zhinan.uniapp.booking.config;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PackageFactCatalogCheckTest {
  private static final String REF = "3f0f3f18-9c2e-4a5b-8f7a-1d2e3c4b5a60";

  @Test
  void acceptsRefsThatAllResolveInsideTheConfiguredMode() {
    assertThat(
            PackageFactCatalogCheck.evaluate(List.of(REF), Map.of(REF, "PRODUCTION"), "PRODUCTION"))
        .isNull();
  }

  @Test
  void refusesARefWithoutAPriceFact() {
    assertThat(PackageFactCatalogCheck.evaluate(List.of(REF), Map.of(), "PRODUCTION"))
        .isEqualTo("PACKAGE_REF_UNKNOWN");
  }

  @Test
  void refusesAFactWrittenForAnotherDataMode() {
    assertThat(
            PackageFactCatalogCheck.evaluate(List.of(REF), Map.of(REF, "SYNTHETIC"), "PRODUCTION"))
        .isEqualTo("PACKAGE_FACT_MODE_MISMATCH");
  }

  @Test
  void reportsTheFirstBrokenRefWhenSeveralAreConfigured() {
    String other = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    assertThat(
            PackageFactCatalogCheck.evaluate(
                List.of(REF, other), Map.of(REF, "PRODUCTION"), "PRODUCTION"))
        .isEqualTo("PACKAGE_REF_UNKNOWN");
  }
}
