package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.*;

import cn.zhinan.admincredentialcrypto.Argon2PasswordService;
import cn.zhinan.adminsecurity.application.AdminLoginService;
import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.port.AdminSessionStore;
import cn.zhinan.adminsecurity.port.AuthenticationEventSink;
import cn.zhinan.adminsecurity.port.CredentialLookup;
import cn.zhinan.adminsecurity.port.PasswordVerifier;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class AdminRuntimeContextTest {
  @Test
  void configDataLoadsOnlyBundledAdminFile() {
    var env = environment();
    AdminRuntimeConfiguration.checkSource(env, AdminRuntimePropertiesTest.properties());
    assertThat(env.getProperty("spring.datasource.url")).isNull();
    assertThat(env.getProperty("server.port")).isNull();
    assertThat(
            env.getPropertySources()
                .contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .isFalse();
    assertThat(
            env.getPropertySources()
                .contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME))
        .isFalse();
    assertThat(env.getProperty("spring.config.location"))
        .isEqualTo("classpath:/application-admin.properties");
    assertThat(env.getProperty("logging.config")).isEqualTo("classpath:logback-admin.xml");
  }

  @Test
  void realCompositionPublishesOnlyVerifiedStoreAndClosesPool() {
    var pool = new MetadataPoolFixture();
    try (var context = context(p -> pool)) {
      context.refresh();
      assertThat(context.getBean(AdminSessionStore.class))
          .isSameAs(context.getBean(VerifiedAdminDataSource.class).sessionStore());
      assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
      assertThat(context.getBean(CredentialLookup.class))
          .isSameAs(context.getBean(VerifiedAdminDataSource.class).credentialLookup());
      assertThat(context.getBean(AuthenticationEventSink.class))
          .isSameAs(context.getBean(VerifiedAdminDataSource.class).authenticationEvents());
      assertThat(context.getBean(PasswordVerifier.class)).isInstanceOf(Argon2PasswordService.class);
      assertThat(context.getBean(AdminSessionService.class)).isNotNull();
      assertThat(context.getBean(AdminLoginService.class)).isNotNull();
      assertThat(context.getBeanDefinitionNames())
          .noneMatch(n -> n.toLowerCase().contains("public") || n.toLowerCase().contains("flyway"));
      assertThat(pool.poolsClosed).isZero();
    }
    assertThat(pool.poolsClosed).isEqualTo(1);
  }

  @Test
  void failedGuardPreventsContextAndStorePublication() {
    var pool = new MetadataPoolFixture();
    pool.rows.get(AdminDatabaseVerifier.IDENTITY).getFirst()[1] = "root@localhost";
    try (var context = context(p -> pool)) {
      assertThatThrownBy(context::refresh).hasRootCauseMessage("ADMIN_RUNTIME_REFUSED:IDENTITY");
      assertThat(context.isActive()).isFalse();
      assertThat(context.getBeanFactory().containsSingleton("adminSessionStore")).isFalse();
      assertThat(context.getBeanFactory().containsSingleton("credentialLookup")).isFalse();
      assertThat(context.getBeanFactory().containsSingleton("authenticationEvents")).isFalse();
      assertThat(context.getBeanFactory().containsSingleton("adminLoginService")).isFalse();
    }
    assertThat(pool.poolsClosed).isEqualTo(1);
  }

  @Test
  void wrongConfigurationMarkerFailsBeforePoolCreation() {
    var opened = new AtomicInteger();
    try (var context =
        context(
            p -> {
              opened.incrementAndGet();
              return new MetadataPoolFixture();
            })) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "wrong-source", Map.of("southleaf.admin.runtime.config-id", "public")));
      assertThatThrownBy(context::refresh)
          .hasRootCauseMessage("ADMIN_RUNTIME_REFUSED:CONFIG_SOURCE");
      assertThat(opened).hasValue(0);
    }
  }

  @Test
  void lateBeanFailureAlsoClosesVerifiedPool() {
    var pool = new MetadataPoolFixture();
    try (var context = context(p -> pool)) {
      context.registerBean(
          "failureAfterVerification",
          Object.class,
          () -> {
            context.getBean(AdminSessionStore.class);
            throw new IllegalStateException("test-failure");
          });
      assertThatThrownBy(context::refresh).hasRootCauseMessage("test-failure");
      assertThat(context.isActive()).isFalse();
    }
    assertThat(pool.poolsClosed).isEqualTo(1);
  }

  private static StandardEnvironment environment() {
    var env = AdminRuntimeApplication.isolatedEnvironment();
    ConfigDataEnvironmentPostProcessor.applyTo(env);
    return env;
  }

  private static AnnotationConfigApplicationContext context(
      VerifiedAdminDataSource.Factory factory) {
    var context = new AnnotationConfigApplicationContext();
    context.setEnvironment(environment());
    context.registerBean(AdminRuntimeProperties.class, AdminRuntimePropertiesTest::properties);
    context.registerBean(
        AdminRuntimeConfiguration.class, () -> new AdminRuntimeConfiguration(factory));
    return context;
  }
}
