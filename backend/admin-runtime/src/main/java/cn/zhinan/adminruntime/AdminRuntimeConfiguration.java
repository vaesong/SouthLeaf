package cn.zhinan.adminruntime;

import cn.zhinan.admincredentialcrypto.Argon2PasswordService;
import cn.zhinan.admindbpolicy.AdminRuntimeGrantPolicy;
import cn.zhinan.adminruntime.crypto.SecureSessionTokenCodec;
import cn.zhinan.adminsecurity.application.AdminLoginService;
import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.port.AdminSessionStore;
import cn.zhinan.adminsecurity.port.AuthenticationEventSink;
import cn.zhinan.adminsecurity.port.CredentialLookup;
import cn.zhinan.adminsecurity.port.PasswordVerifier;
import cn.zhinan.adminsecurity.port.SessionTokenCodec;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** Explicit composition only. No scanning, auto-configuration, raw DataSource or migration. */
@Configuration(proxyBeanMethods = false)
@Import({
  AdminAuthController.class,
  AdminWebUiController.class,
  AdminWebSecurityConfiguration.class,
  AdminWebServerConfiguration.class
})
class AdminRuntimeConfiguration {
  private final VerifiedAdminDataSource.Factory factory;

  AdminRuntimeConfiguration() {
    this(VerifiedAdminDataSource::createPool);
  }

  AdminRuntimeConfiguration(VerifiedAdminDataSource.Factory factory) {
    this.factory = factory;
  }

  @Bean(destroyMethod = "close")
  VerifiedAdminDataSource verifiedAdminDataSource(
      AdminRuntimeProperties properties, Environment env) {
    checkSource(env, properties);
    return VerifiedAdminDataSource.open(properties, factory);
  }

  static void checkSource(Environment env, AdminRuntimeProperties properties) {
    String expectedWebType = properties.web().enabled() ? "servlet" : "none";
    if (!AdminRuntimeGrantPolicy.CONTRACT_ID.equals(
            env.getProperty("southleaf.admin.runtime.config-id"))
        || !expectedWebType.equals(env.getProperty("spring.main.web-application-type"))
        || !"false".equals(env.getProperty("spring.flyway.enabled"))
        || !"never".equals(env.getProperty("spring.sql.init.mode")))
      throw new AdminStartupException(AdminStartupException.Reason.CONFIG_SOURCE);
  }

  @Bean
  AdminSessionStore adminSessionStore(VerifiedAdminDataSource verified) {
    return verified.sessionStore();
  }

  @Bean
  CredentialLookup credentialLookup(VerifiedAdminDataSource verified) {
    return verified.credentialLookup();
  }

  @Bean
  AuthenticationEventSink authenticationEvents(VerifiedAdminDataSource verified) {
    return verified.authenticationEvents();
  }

  @Bean
  Clock adminClock() {
    return Clock.systemUTC();
  }

  @Bean
  AdminLoginRateLimiter adminLoginRateLimiter(Clock clock) {
    return new AdminLoginRateLimiter(clock);
  }

  @Bean
  SessionTokenCodec sessionTokenCodec() {
    return new SecureSessionTokenCodec();
  }

  @Bean
  PasswordVerifier passwordVerifier() {
    return new Argon2PasswordService();
  }

  @Bean
  AdminSessionService adminSessionService(
      AdminSessionStore store, SessionTokenCodec tokens, Clock clock) {
    return new AdminSessionService(store, tokens, clock, Duration.ofHours(2));
  }

  @Bean
  AdminLoginService adminLoginService(
      CredentialLookup lookup,
      PasswordVerifier verifier,
      AdminSessionService sessions,
      AuthenticationEventSink events,
      Clock clock) {
    return new AdminLoginService(lookup, verifier, sessions, events, clock);
  }
}
