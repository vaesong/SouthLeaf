package cn.zhinan.uniapp.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.uniapp.booking.domain.PublicBookingRules;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class PublicBookingRulesTest {
  private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

  @Test
  void windowIsExpressedInTheBusinessZoneAndCappedAtThirtyOneDays() {
    Instant[] window = PublicBookingRules.window("2026-10-26", "2026-10-27", TOKYO, NOW);
    assertThat(window[0]).isEqualTo(Instant.parse("2026-10-25T15:00:00Z"));
    assertThat(window[1]).isEqualTo(Instant.parse("2026-10-27T15:00:00Z"));
    assertThat(PublicBookingRules.window("2026-10-25", "2026-11-24", TOKYO, NOW)).hasSize(2);
    assertThatThrownBy(() -> PublicBookingRules.window("2026-10-25", "2026-11-25", TOKYO, NOW))
        .isInstanceOf(PublicBookingException.class);
    assertThatThrownBy(() -> PublicBookingRules.window("2026-10-27", "2026-10-26", TOKYO, NOW))
        .isInstanceOf(PublicBookingException.class);
    assertThatThrownBy(() -> PublicBookingRules.window("2026-10-01", "2026-10-02", TOKYO, NOW))
        .isInstanceOf(PublicBookingException.class);
    assertThatThrownBy(() -> PublicBookingRules.window("26-10-26", "2026-10-27", TOKYO, NOW))
        .isInstanceOf(PublicBookingException.class);
  }

  @Test
  void displayStatusDerivesExpiryWithoutRewritingTheStoredState() {
    Instant expiresAt = NOW.plus(Duration.ofHours(48));
    assertThat(PublicBookingRules.displayStatus("PENDING_CONFIRMATION", expiresAt, NOW))
        .isEqualTo("PENDING_CONFIRMATION");
    assertThat(PublicBookingRules.displayStatus("PENDING_CONFIRMATION", expiresAt, expiresAt))
        .isEqualTo("EXPIRED");
    assertThat(PublicBookingRules.displayStatus("CONFIRMED", expiresAt, expiresAt.plusSeconds(60)))
        .isEqualTo("CONFIRMED");
  }

  @Test
  void amountLabelNeverImpliesAPayment() {
    assertThat(PublicBookingRules.amountLabel("JPY", 12345)).isEqualTo("JP\u00a512,345");
    assertThat(PublicBookingRules.amountLabel("USD", 12345)).isEqualTo("USD 123.45");
  }

  @Test
  void identifiersAndKeysAreStrictlyValidated() {
    assertThat(PublicBookingRules.idempotencyKey("mp-000001")).isEqualTo("mp-000001");
    assertThatThrownBy(() -> PublicBookingRules.idempotencyKey("short"))
        .isInstanceOf(PublicBookingException.class);
    assertThatThrownBy(() -> PublicBookingRules.slotId("bad id"))
        .isInstanceOf(PublicBookingException.class);
    assertThatThrownBy(() -> PublicBookingRules.packageVersionRef(""))
        .isInstanceOf(PublicBookingException.class);
    assertThatThrownBy(() -> PublicBookingRules.loginCode("abc"))
        .isInstanceOf(PublicBookingException.class);
  }

  @Test
  void slotLabelIsLocalToTheBusinessZone() {
    assertThat(
            PublicBookingRules.slotLabel(
                Instant.parse("2026-10-26T01:00:00Z"),
                Instant.parse("2026-10-26T02:30:00Z"),
                TOKYO))
        .isEqualTo("2026-10-26 10:00-11:30");
  }
}
