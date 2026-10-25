package cn.zhinan.uniapp.booking.identity;

import cn.zhinan.uniapp.booking.PublicBookingException;

/**
 * Disposable-fixture sink. It is only wired when the runtime is explicitly synthetic and the
 * identity sink is explicitly {@code TEST}; a production mode can never reach this class.
 */
public final class TestSinkSubjectExchange implements SubjectExchange {
  private static final String PREFIX = "test-";

  @Override
  public String provider() {
    return "WECHAT";
  }

  @Override
  public String subject(String loginCode) {
    if (!loginCode.startsWith(PREFIX)) throw rejected();
    String subject = loginCode.substring(PREFIX.length());
    if (!subject.matches("[A-Za-z0-9._:-]{3,64}")) throw rejected();
    return "SYNTHETIC:" + subject;
  }

  private static PublicBookingException rejected() {
    return new PublicBookingException(
        PublicBookingException.ErrorCode.IDENTITY_REJECTED, "Synthetic code rejected");
  }
}
