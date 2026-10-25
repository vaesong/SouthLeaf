package cn.zhinan.uniapp.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.uniapp.booking.session.PublicSessionTokens;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PublicSessionTokensTest {
  private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
  private static final String CUSTOMER = "3f2a1991-0000-4000-8000-000000000001";

  @Test
  void issuedTokenRoundTripsToTheSameCustomer() {
    PublicSessionTokens tokens = new PublicSessionTokens(SECRET, Duration.ofMinutes(30));
    var issued = tokens.issue(CUSTOMER, NOW);
    assertThat(tokens.customerId(issued.token(), NOW.plusSeconds(60))).isEqualTo(CUSTOMER);
    assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
  }

  @Test
  void tamperedSignatureAndPayloadAreRejected() {
    PublicSessionTokens tokens = new PublicSessionTokens(SECRET, Duration.ofMinutes(30));
    String token = tokens.issue(CUSTOMER, NOW).token();
    String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
    assertThatThrownBy(() -> tokens.customerId(tampered, NOW))
        .isInstanceOf(PublicBookingException.class)
        .extracting(error -> ((PublicBookingException) error).getErrorCode())
        .isEqualTo(PublicBookingException.ErrorCode.SESSION_REQUIRED);
    assertThatThrownBy(() -> tokens.customerId(token, NOW.plus(Duration.ofHours(2))))
        .isInstanceOf(PublicBookingException.class);
    assertThatThrownBy(() -> tokens.customerId("not-a-token", NOW))
        .isInstanceOf(PublicBookingException.class);
    assertThatThrownBy(() -> tokens.customerId(null, NOW))
        .isInstanceOf(PublicBookingException.class);
  }

  @Test
  void shortSecretIsRefusedAtConstruction() {
    assertThatThrownBy(() -> new PublicSessionTokens("short", Duration.ofMinutes(30)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
