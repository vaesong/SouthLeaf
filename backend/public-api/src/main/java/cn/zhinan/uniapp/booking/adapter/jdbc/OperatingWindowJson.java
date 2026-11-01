package cn.zhinan.uniapp.booking.adapter.jdbc;

import cn.zhinan.uniapp.booking.PublicBookingException;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.OperatingWindow;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a stored operating-rule payload. A broken payload is a dependency failure, not
 * "unconfigured".
 */
final class OperatingWindowJson {
  private static final Pattern FIELD =
      Pattern.compile(
          "\"(minAdvanceHours|maxAdvanceDays|pendingExpiryHours|slotDurationMinutes)\"\\s*:\\s*(-?\\d+)");

  private OperatingWindowJson() {}

  static OperatingWindow parse(String json) {
    if (json == null || json.length() > 512) throw broken();
    Map<String, Integer> values = new LinkedHashMap<>();
    String rest = json;
    while (true) {
      Matcher matcher = FIELD.matcher(rest);
      if (!matcher.find()) break;
      int number;
      try {
        number = Integer.parseInt(matcher.group(2));
      } catch (NumberFormatException invalid) {
        throw broken();
      }
      if (values.put(matcher.group(1), number) != null) throw broken();
      rest = rest.substring(0, matcher.start()) + rest.substring(matcher.end());
    }
    if (!rest.replaceAll("[\\s{},]", "").isEmpty() || values.size() != 4) throw broken();
    OperatingWindow window =
        new OperatingWindow(
            1,
            values.get("minAdvanceHours"),
            values.get("maxAdvanceDays"),
            values.get("pendingExpiryHours"),
            values.get("slotDurationMinutes"));
    if (window.minAdvanceHours() < 0
        || window.minAdvanceHours() > 168
        || window.maxAdvanceDays() < 1
        || window.maxAdvanceDays() > 180
        || window.pendingExpiryHours() < 1
        || window.pendingExpiryHours() > 168
        || window.slotDurationMinutes() < 30
        || window.slotDurationMinutes() > 480) throw broken();
    return window;
  }

  private static PublicBookingException broken() {
    return new PublicBookingException(
        PublicBookingException.ErrorCode.SERVICE_UNAVAILABLE, "RULE_PAYLOAD_INVALID");
  }
}
