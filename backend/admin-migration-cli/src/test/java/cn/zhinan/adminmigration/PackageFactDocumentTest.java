package cn.zhinan.adminmigration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PackageFactDocumentTest {
  private static final String VALID =
      """
      {
        "dataMode": "PRODUCTION",
        "facts": [
          {
            "versionRef": "3f0f3f18-9c2e-4a5b-8f7a-1d2e3c4b5a60",
            "displayName": "家庭肖像 A",
            "currencyCode": "JPY",
            "amountMinorUnits": 38000,
            "priceVersion": "2026-11"
          }
        ]
      }
      """;

  @Test
  void acceptsAWellFormedBatch() {
    var document = PackageFactDocument.parse(VALID);
    assertThat(document.dataMode()).isEqualTo("PRODUCTION");
    assertThat(document.facts()).hasSize(1);
    assertThat(document.facts().get(0).amountMinorUnits()).isEqualTo(38000);
  }

  @Test
  void refusesASecondDataModeOnTheFact() {
    String json =
        VALID.replace(
            "\"priceVersion\": \"2026-11\"", "\"priceVersion\": \"v\",\"dataMode\":\"SYNTHETIC\"");
    assertThatThrownBy(() -> PackageFactDocument.parse(json)).hasMessage("FACT_HAS_UNKNOWN_FIELD");
  }

  @Test
  void refusesAnUnknownDocumentField() {
    String json = VALID.replace("{\n  \"dataMode\"", "{\n  \"note\": \"x\",\n  \"dataMode\"");
    assertThatThrownBy(() -> PackageFactDocument.parse(json)).hasMessage("FILE_HAS_UNKNOWN_FIELD");
  }

  @Test
  void refusesMalformedValues() {
    for (String replacement :
        new String[] {
          "\"versionRef\": \"not-a-uuid\"",
          "\"currencyCode\": \"jpy\"",
          "\"amountMinorUnits\": -1",
          "\"displayName\": \"\"",
          "\"priceVersion\": \"\""
        }) {
      String key = replacement.substring(0, replacement.indexOf(':'));
      String original = key + ": " + valueOf(key);
      assertThatThrownBy(() -> PackageFactDocument.parse(VALID.replace(original, replacement)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void refusesAnEmptyOrUnknownBatch() {
    assertThatThrownBy(() -> PackageFactDocument.parse("{}")).hasMessage("FILE_MODE_INVALID");
    assertThatThrownBy(() -> PackageFactDocument.parse("[]")).hasMessage("FILE_NOT_OBJECT");
    assertThatThrownBy(() -> PackageFactDocument.parse("not json")).hasMessage("FILE_NOT_JSON");
    assertThatThrownBy(
            () ->
                PackageFactDocument.parse(
                    "{\"dataMode\":\"DEMO\",\"facts\":[{\"versionRef\":\"3f0f3f18-9c2e-4a5b-8f7a-1d2e3c4b5a60\"}]}"))
        .hasMessage("FILE_MODE_INVALID");
  }

  @Test
  void refusesDuplicateVersionRefsInsideOneBatch() {
    String fact =
        """
        {"versionRef":"3f0f3f18-9c2e-4a5b-8f7a-1d2e3c4b5a60","displayName":"A","currencyCode":"JPY","amountMinorUnits":1,"priceVersion":"v"}
        """;
    String json = "{\"dataMode\":\"SYNTHETIC\",\"facts\":[" + fact + "," + fact + "]}";
    assertThatThrownBy(() -> PackageFactDocument.parse(json))
        .hasMessage("FACT_VERSION_REF_DUPLICATED");
  }

  private static String valueOf(String key) {
    return switch (key) {
      case "\"versionRef\"" -> "\"3f0f3f18-9c2e-4a5b-8f7a-1d2e3c4b5a60\"";
      case "\"displayName\"" -> "\"家庭肖像 A\"";
      case "\"currencyCode\"" -> "\"JPY\"";
      case "\"amountMinorUnits\"" -> "38000";
      case "\"priceVersion\"" -> "\"2026-11\"";
      default -> throw new IllegalArgumentException(key);
    };
  }
}
