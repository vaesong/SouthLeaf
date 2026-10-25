package cn.zhinan.uniapp.booking.identity;

/**
 * Exchanges a one-time login code for a provider subject. The raw subject never leaves the server.
 */
public interface SubjectExchange {
  String provider();

  String subject(String loginCode);
}
