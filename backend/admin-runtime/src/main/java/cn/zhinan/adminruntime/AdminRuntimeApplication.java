package cn.zhinan.adminruntime;

import cn.zhinan.admindbpolicy.AdminRuntimeGrantPolicy;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** C2 verified non-Web composition. No listener, login endpoint or account provisioning. */
public final class AdminRuntimeApplication {
  private AdminRuntimeApplication() {}

  public static void main(String[] args) {
    System.exit(run(args, System.getenv(), System.getProperties()));
  }

  static int run(String[] args, Map<String, String> environment, Properties system) {
    try {
      if (args.length > 1 || (args.length == 1 && !"--check".equals(args[0])))
        throw new AdminStartupException(AdminStartupException.Reason.ARGUMENTS);
      var properties = AdminRuntimeProperties.load(environment, system);
      try (var context = start(properties)) {
        String runtimeMode =
            !properties.web().enabled()
                ? "NON_WEB"
                : properties.content().enabled() ? "HTTPS_WEB_CONTENT" : "HTTPS_WEB";
        if (properties.outbox().enabled()) runtimeMode += "_OUTBOX";
        if (properties.booking().enabled()) runtimeMode += "_BOOKING";
        System.out.println(
            "ADMIN_RUNTIME_READY:" + AdminRuntimeGrantPolicy.CONTRACT_ID + ":" + runtimeMode);
        if (args.length == 0) awaitShutdown(context);
      }
      return 0;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      System.err.println("ADMIN_RUNTIME_REFUSED:INTERRUPTED");
      return 2;
    } catch (RuntimeException ex) {
      System.err.println("ADMIN_RUNTIME_REFUSED:" + reason(ex));
      return 2;
    }
  }

  static ConfigurableApplicationContext start(AdminRuntimeProperties properties) {
    // Optional capabilities are composed only when explicitly enabled and verified.
    var sources = new ArrayList<Class<?>>();
    sources.add(AdminRuntimeConfiguration.class);
    if (properties.content().enabled()) sources.add(AdminContentConfiguration.class);
    if (properties.outbox().enabled()) sources.add(AdminOutboxConfiguration.class);
    if (properties.booking().enabled()) sources.add(AdminBookingConfiguration.class);
    var application = new SpringApplication(sources.toArray(Class<?>[]::new));
    application.setEnvironment(isolatedEnvironment(properties.web()));
    application.setWebApplicationType(
        properties.web().enabled() ? WebApplicationType.SERVLET : WebApplicationType.NONE);
    application.setBannerMode(Banner.Mode.OFF);
    application.setLogStartupInfo(false);
    application.setAddCommandLineProperties(false);
    application.setRegisterShutdownHook(false);
    application.addInitializers(
        context -> {
          context.getBeanFactory().registerSingleton("adminRuntimeProperties", properties);
          context.getBeanFactory().registerSingleton("adminWebProperties", properties.web());
        });
    return application.run();
  }

  static StandardEnvironment isolatedEnvironment() {
    return isolatedEnvironment(AdminWebProperties.load(Map.of()));
  }

  static StandardEnvironment isolatedEnvironment(AdminWebProperties web) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("admin-bootstrap", bootstrapProperties(web)));
    return environment;
  }

  private static Map<String, Object> bootstrapProperties(AdminWebProperties web) {
    var properties = new java.util.HashMap<String, Object>();
    properties.put("spring.config.location", "classpath:/application-admin.properties");
    properties.put("spring.main.web-application-type", web.enabled() ? "servlet" : "none");
    properties.put("spring.main.banner-mode", "off");
    properties.put("logging.config", "classpath:logback-admin.xml");
    if (web.enabled()) {
      properties.put("server.address", "localhost");
      properties.put("server.port", web.port());
      properties.put("server.ssl.enabled", true);
      properties.put("server.ssl.key-store", web.keyStorePath().toUri().toString());
      properties.put("server.ssl.key-store-password", web.keyStorePassword());
      properties.put("server.ssl.key-store-type", web.keyStoreType());
    }
    return Map.copyOf(properties);
  }

  private static void awaitShutdown(ConfigurableApplicationContext context)
      throws InterruptedException {
    var stopped = new CountDownLatch(1);
    var hook =
        new Thread(
            () -> {
              try {
                context.close();
              } finally {
                stopped.countDown();
              }
            },
            "admin-runtime-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);
    try {
      stopped.await();
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException shutdownInProgress) {
        /* JVM owns the hook. */
      }
    }
  }

  static String reason(Throwable exception) {
    StringBuilder types = new StringBuilder();
    Throwable current = exception;
    Throwable deepest = null;
    for (int n = 0; n < 8 && current != null; n++, current = current.getCause()) {
      if (current instanceof AdminStartupException failure) return failure.reason().name();
      deepest = current;
      String type =
          current
              .getClass()
              .getSimpleName()
              .toUpperCase(java.util.Locale.ROOT)
              .replaceAll("[^A-Z]+", "_")
              .replaceAll("^_+|_+$", "");
      if (!type.matches("[A-Z_]{1,64}")) return AdminStartupException.Reason.INTERNAL.name();
      if (!types.isEmpty()) types.append("_CAUSE_");
      types.append(type);
      if (types.length() > 192) return AdminStartupException.Reason.INTERNAL.name();
    }
    if (types.isEmpty()) return AdminStartupException.Reason.INTERNAL.name();
    StringBuilder reason = new StringBuilder("INTERNAL_").append(types);
    // 仅附加根因首个栈帧的简单类名，不输出消息、堆栈、行号或路径。
    if (deepest != null) {
      StackTraceElement[] frames = deepest.getStackTrace();
      if (frames.length > 0) {
        String source = frames[0].getClassName();
        int packageSeparator = source.lastIndexOf('.');
        if (packageSeparator >= 0) source = source.substring(packageSeparator + 1);
        int nestedClass = source.indexOf('$');
        if (nestedClass >= 0) source = source.substring(0, nestedClass);
        source =
            source
                .toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z]+", "_")
                .replaceAll("^_+|_+$", "");
        if (source.matches("[A-Z_]{1,64}")) reason.append("_AT_").append(source);
      }
    }
    return reason.toString();
  }
}
