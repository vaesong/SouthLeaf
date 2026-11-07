package cn.zhinan.uniapp.booking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicBookingPropertiesTest {
  @Test
  void productionRefusesTheTestIdentityAndAPartialWeChatConfiguration() {
    Map<String, String> productionTest = configured("PRODUCTION", "TEST");
    assertThatThrownBy(() -> PublicBookingProperties.load(productionTest, "southleaf_reader"))
        .isInstanceOf(PublicBookingStartupException.class);

    Map<String, String> wechatWithoutSecret = configured("SYNTHETIC", "WECHAT");
    wechatWithoutSecret.put("SOUTHLEAF_PUBLIC_BOOKING_WECHAT_APP_ID", "wx0123456789abcdef");
    assertThatThrownBy(() -> PublicBookingProperties.load(wechatWithoutSecret, "southleaf_reader"))
        .isInstanceOf(PublicBookingStartupException.class);
  }

  @Test
  void syntheticTestSinkAndProductionWeChatAreExplicit() {
    var synthetic =
        PublicBookingProperties.load(configured("SYNTHETIC", "TEST"), "southleaf_reader");
    assertThat(synthetic.identitySink()).isEqualTo("TEST");
    assertThat(synthetic.dataMode()).isEqualTo("SYNTHETIC");
    assertThat(synthetic.wechatAppSecret()).isNull();

    Map<String, String> production = configured("PRODUCTION", "WECHAT");
    production.put("SOUTHLEAF_PUBLIC_BOOKING_WECHAT_APP_ID", "wx0123456789abcdef");
    production.put("SOUTHLEAF_PUBLIC_BOOKING_WECHAT_APP_SECRET", "app-secret-not-logged");
    var loaded = PublicBookingProperties.load(production, "southleaf_reader");
    assertThat(loaded.identitySink()).isEqualTo("WECHAT");
    assertThat(loaded.toString()).doesNotContain("app-secret-not-logged");
  }

  private static Map<String, String> configured(String dataMode, String sink) {
    Map<String, String> env = new HashMap<>();
    env.put("SOUTHLEAF_PUBLIC_BOOKING_MODE", "enabled");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DB_URL", "jdbc:mysql://localhost:3307/southleaf_booking");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DB_USERNAME", "southleaf_public_booking_writer");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DB_PASSWORD", "secret-password-value");
    env.put(
        "SOUTHLEAF_PUBLIC_BOOKING_DB_EXPECTED_USER", "southleaf_public_booking_writer@localhost");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DATA_MODE", dataMode);
    env.put("SOUTHLEAF_PUBLIC_BOOKING_IDENTITY_SINK", sink);
    env.put("SOUTHLEAF_PUBLIC_BOOKING_APP_SCOPE", "studio");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_SESSION_SECRET", "0123456789abcdef0123456789abcdef");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_SESSION_MINUTES", "30");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_REQUEST_HOURS", "48");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_BUSINESS_ZONE", "Asia/Shanghai");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_PACKAGE_REFS", "pkg-demo=演示套餐");
    return env;
  }
}
