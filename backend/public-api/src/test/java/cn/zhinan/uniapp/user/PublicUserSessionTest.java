package cn.zhinan.uniapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.appuser.AppUserException;
import cn.zhinan.appuser.AppUserRules;
import cn.zhinan.appuser.AppUserTokens;
import cn.zhinan.uniapp.booking.PublicBookingException;
import cn.zhinan.uniapp.booking.domain.PublicBookingRules;
import cn.zhinan.uniapp.booking.session.PublicSessionTokens;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PublicUserSessionTest {
  @Test
  void userFingerprintAndTokenStaySeparateFromBooking() {
    String secret = "0123456789abcdef0123456789abcdef";
    Instant now = Instant.parse("2026-11-21T04:00:00Z");
    String id = "11111111-1111-4111-8111-111111111111";
    assertThat(AppUserRules.fingerprint("WECHAT", "mini-app", "subject-1"))
        .isEqualTo(PublicBookingRules.fingerprint("WECHAT", "mini-app", "subject-1"));
    String userToken = new AppUserTokens(secret, Duration.ofHours(12)).issue(id, now).token();
    String bookingToken =
        new PublicSessionTokens(secret, Duration.ofHours(12)).issue(id, now).token();
    assertThatThrownBy(
            () -> new PublicSessionTokens(secret, Duration.ofHours(12)).customerId(userToken, now))
        .isInstanceOf(PublicBookingException.class);
    assertThatThrownBy(
            () -> new AppUserTokens(secret, Duration.ofHours(12)).userId(bookingToken, now))
        .isInstanceOf(AppUserException.class);
  }
}
