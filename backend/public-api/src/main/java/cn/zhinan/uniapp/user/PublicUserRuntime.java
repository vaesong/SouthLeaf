package cn.zhinan.uniapp.user;

import cn.zhinan.appuser.AppUserService;
import cn.zhinan.appuser.AppUserTokens;
import cn.zhinan.appuser.JdbcAppUserStore;
import cn.zhinan.uniapp.booking.PublicBookingException;
import cn.zhinan.uniapp.booking.identity.SubjectExchange;
import cn.zhinan.uniapp.booking.identity.TestSinkSubjectExchange;
import cn.zhinan.uniapp.booking.identity.WeChatSubjectExchange;

/** Owns the user pool. A disabled entry opens no connection, and booking can stay closed. */
public final class PublicUserRuntime implements AutoCloseable {
  private final VerifiedPublicUserDataSource dataSource;
  private final AppUserService service;
  private final AppUserTokens tokens;
  private final SubjectExchange exchange;
  private final PublicUserProperties properties;

  private PublicUserRuntime(
      VerifiedPublicUserDataSource dataSource,
      AppUserService service,
      AppUserTokens tokens,
      SubjectExchange exchange,
      PublicUserProperties properties) {
    this.dataSource = dataSource;
    this.service = service;
    this.tokens = tokens;
    this.exchange = exchange;
    this.properties = properties;
  }

  public static PublicUserRuntime open(PublicUserProperties properties) {
    if (!properties.enabled()) {
      return new PublicUserRuntime(null, null, null, null, properties);
    }
    VerifiedPublicUserDataSource dataSource = VerifiedPublicUserDataSource.open(properties);
    boolean accepted = false;
    try {
      SubjectExchange exchange =
          "TEST".equals(properties.identitySink())
              ? new TestSinkSubjectExchange()
              : new WeChatSubjectExchange(properties.wechatAppId(), properties.wechatAppSecret());
      var runtime =
          new PublicUserRuntime(
              dataSource,
              new AppUserService(
                  new JdbcAppUserStore(dataSource.dataSource()), properties.appScope()),
              new AppUserTokens(properties.sessionSecret(), properties.sessionLifetime()),
              exchange,
              properties);
      accepted = true;
      return runtime;
    } finally {
      if (!accepted) dataSource.close();
    }
  }

  public boolean enabled() {
    return properties.enabled();
  }

  public AppUserService service() {
    if (service == null) throw cn.zhinan.appuser.AppUserException.unavailable();
    return service;
  }

  public AppUserTokens tokens() {
    if (tokens == null) throw cn.zhinan.appuser.AppUserException.unavailable();
    return tokens;
  }

  public String subject(String loginCode) {
    if (exchange == null) throw cn.zhinan.appuser.AppUserException.unavailable();
    try {
      return exchange.subject(loginCode);
    } catch (PublicBookingException rejected) {
      throw cn.zhinan.appuser.AppUserException.identityRejected();
    }
  }

  public String dataMode() {
    return properties.enabled() ? properties.publicDataMode() : "production";
  }

  @Override
  public void close() {
    if (dataSource != null) dataSource.close();
  }

  @Override
  public String toString() {
    return "PublicUserRuntime[" + (dataSource == null ? "disabled" : "verified") + "]";
  }
}
