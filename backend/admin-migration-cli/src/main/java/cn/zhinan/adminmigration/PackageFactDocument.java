package cn.zhinan.adminmigration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One import file of immutable price facts.
 *
 * <p>The file declares its data mode once, at the top, so a batch cannot mix synthetic and
 * production prices; a per-fact mode is rejected as a typo rather than honoured.
 */
record PackageFactDocument(String dataMode, List<Fact> facts) {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> MODES = Set.of("SYNTHETIC", "PRODUCTION");

  record Fact(
      String versionRef,
      String displayName,
      String currencyCode,
      long amountMinorUnits,
      String priceVersion) {}

  static PackageFactDocument parse(String json) {
    final JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (Exception malformed) {
      throw new IllegalArgumentException("FILE_NOT_JSON");
    }
    if (root == null || !root.isObject()) throw new IllegalArgumentException("FILE_NOT_OBJECT");
    rejectUnknownFields(root, Set.of("dataMode", "facts"), "FILE_HAS_UNKNOWN_FIELD");
    JsonNode modeNode = root.get("dataMode");
    String dataMode = modeNode != null && modeNode.isTextual() ? modeNode.asText() : "";
    if (!MODES.contains(dataMode)) throw new IllegalArgumentException("FILE_MODE_INVALID");
    JsonNode rows = root.get("facts");
    if (rows == null || !rows.isArray() || rows.isEmpty())
      throw new IllegalArgumentException("FILE_HAS_NO_FACTS");
    List<Fact> facts = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode row : rows) {
      if (!row.isObject()) throw new IllegalArgumentException("FACT_NOT_OBJECT");
      rejectUnknownFields(
          row,
          Set.of("versionRef", "displayName", "currencyCode", "amountMinorUnits", "priceVersion"),
          "FACT_HAS_UNKNOWN_FIELD");
      Fact fact =
          new Fact(
              text(row, "versionRef"),
              text(row, "displayName"),
              text(row, "currencyCode"),
              number(row, "amountMinorUnits"),
              text(row, "priceVersion"));
      validate(fact);
      if (!seen.add(fact.versionRef()))
        throw new IllegalArgumentException("FACT_VERSION_REF_DUPLICATED");
      facts.add(fact);
    }
    return new PackageFactDocument(dataMode, List.copyOf(facts));
  }

  private static void validate(Fact fact) {
    if (!fact.versionRef().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
      throw new IllegalArgumentException("FACT_VERSION_REF_INVALID");
    if (fact.displayName().isBlank() || fact.displayName().length() > 120)
      throw new IllegalArgumentException("FACT_DISPLAY_NAME_INVALID");
    if (!fact.currencyCode().matches("[A-Z]{3}"))
      throw new IllegalArgumentException("FACT_CURRENCY_INVALID");
    if (fact.amountMinorUnits() < 0 || fact.amountMinorUnits() > 1_000_000_000_000L)
      throw new IllegalArgumentException("FACT_AMOUNT_INVALID");
    if (fact.priceVersion().isBlank() || fact.priceVersion().length() > 64)
      throw new IllegalArgumentException("FACT_PRICE_VERSION_INVALID");
  }

  private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String reason) {
    var names = node.fieldNames();
    while (names.hasNext()) {
      if (!allowed.contains(names.next())) throw new IllegalArgumentException(reason);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) throw new IllegalArgumentException("FIELD_MISSING");
    return value.asText();
  }

  private static long number(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber())
      throw new IllegalArgumentException("FIELD_MISSING");
    return value.asLong();
  }
}
