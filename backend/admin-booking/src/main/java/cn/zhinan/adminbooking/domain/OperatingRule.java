package cn.zhinan.adminbooking.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One saved operating-rule version. Capacity stays 1 and is not part of the payload. The record
 * does not read the environment or the clock.
 */
public record OperatingRule(
    int minAdvanceHours, int maxAdvanceDays, int pendingExpiryHours, int slotDurationMinutes) {
  public static final int CAPACITY = 1;

  private static final Pattern FIELD =
      Pattern.compile(
          "\"(minAdvanceHours|maxAdvanceDays|pendingExpiryHours|slotDurationMinutes)\"\\s*:\\s*(-?\\d+)");

  public OperatingRule {
    require(minAdvanceHours, 0, 168, "RULE_FIELD_INVALID_MIN_ADVANCE_HOURS");
    require(maxAdvanceDays, 1, 180, "RULE_FIELD_INVALID_MAX_ADVANCE_DAYS");
    require(pendingExpiryHours, 1, 168, "RULE_FIELD_INVALID_PENDING_EXPIRY_HOURS");
    require(slotDurationMinutes, 30, 480, "RULE_FIELD_INVALID_SLOT_DURATION");
  }

  public String canonicalJson() {
    return "{\"minAdvanceHours\":"
        + minAdvanceHours
        + ",\"maxAdvanceDays\":"
        + maxAdvanceDays
        + ",\"pendingExpiryHours\":"
        + pendingExpiryHours
        + ",\"slotDurationMinutes\":"
        + slotDurationMinutes
        + "}";
  }

  public static OperatingRule fromStoredJson(String json) {
    try {
      return parse(json);
    } catch (BookingException invalid) {
      throw new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "RULE_PAYLOAD_INVALID");
    } catch (RuntimeException invalid) {
      throw new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "RULE_PAYLOAD_INVALID");
    }
  }

  private static OperatingRule parse(String json) {
    if (json == null || json.length() > 512)
      throw new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "RULE_PAYLOAD_INVALID");
    Map<String, Integer> values = new LinkedHashMap<>();
    String rest = json;
    while (true) {
      Matcher matcher = FIELD.matcher(rest);
      if (!matcher.find()) break;
      if (values.put(matcher.group(1), Integer.valueOf(matcher.group(2))) != null)
        throw new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "RULE_PAYLOAD_INVALID");
      rest = rest.substring(0, matcher.start()) + rest.substring(matcher.end());
    }
    if (!rest.replaceAll("[\\s{},]", "").isEmpty() || values.size() != 4)
      throw new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "RULE_PAYLOAD_INVALID");
    return new OperatingRule(
        values.get("minAdvanceHours"),
        values.get("maxAdvanceDays"),
        values.get("pendingExpiryHours"),
        values.get("slotDurationMinutes"));
  }

  private static void require(int value, int min, int max, String reason) {
    if (value < min || value > max)
      throw new BookingException(BookingFailure.INVALID_REQUEST, reason);
  }
}
