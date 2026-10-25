package cn.zhinan.uniapp.booking.config;

import cn.zhinan.uniapp.booking.adapter.jdbc.JdbcPublicBookingRepository;
import cn.zhinan.uniapp.booking.application.PublicBookingService;
import cn.zhinan.uniapp.booking.identity.SubjectExchange;
import cn.zhinan.uniapp.booking.identity.TestSinkSubjectExchange;
import cn.zhinan.uniapp.booking.identity.WeChatSubjectExchange;
import cn.zhinan.uniapp.booking.session.PublicSessionTokens;
import java.time.Clock;

/** Owns the intake pool so a disabled entry costs no connection and no background thread. */
public final class PublicBookingRuntime implements AutoCloseable {
  private final VerifiedPublicBookingDataSource dataSource;
  private final PublicBookingService service;

  private PublicBookingRuntime(
      VerifiedPublicBookingDataSource dataSource, PublicBookingService service) {
    this.dataSource = dataSource;
    this.service = service;
  }

  public static PublicBookingRuntime open(PublicBookingProperties properties, Clock clock) {
    if (!properties.enabled()) {
      return new PublicBookingRuntime(
          null, new PublicBookingService(properties, null, null, null, clock));
    }
    VerifiedPublicBookingDataSource dataSource = VerifiedPublicBookingDataSource.open(properties);
    boolean accepted = false;
    try {
      SubjectExchange exchange =
          "TEST".equals(properties.identitySink())
              ? new TestSinkSubjectExchange()
              : new WeChatSubjectExchange(properties.wechatAppId(), properties.wechatAppSecret());
      PublicSessionTokens tokens =
          new PublicSessionTokens(properties.sessionSecret(), properties.sessionLifetime());
      PublicBookingService service =
          new PublicBookingService(
              properties,
              new JdbcPublicBookingRepository(dataSource.dataSource()),
              exchange,
              tokens,
              clock);
      var runtime = new PublicBookingRuntime(dataSource, service);
      accepted = true;
      return runtime;
    } finally {
      if (!accepted) dataSource.close();
    }
  }

  public PublicBookingService service() {
    return service;
  }

  @Override
  public void close() {
    if (dataSource != null) dataSource.close();
  }

  @Override
  public String toString() {
    return "PublicBookingRuntime[" + (dataSource == null ? "disabled" : "verified") + "]";
  }
}
