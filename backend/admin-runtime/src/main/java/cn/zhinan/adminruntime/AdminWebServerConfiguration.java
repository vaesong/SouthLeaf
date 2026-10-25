package cn.zhinan.adminruntime;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
import java.util.EnumSet;
import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Minimal explicit HTTPS/MVC bootstrap; no broad Boot auto-configuration or default user. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
@EnableWebMvc
@EnableWebSecurity
class AdminWebServerConfiguration {
  @Bean
  ServletWebServerFactory adminServletWebServerFactory(AdminWebProperties properties) {
    if (!properties.enabled())
      throw new AdminStartupException(AdminStartupException.Reason.CONFIGURATION);
    var factory = new TomcatServletWebServerFactory();
    factory.setAddress(properties.bindAddress());
    factory.setPort(properties.port());
    // TLS is present only when this profile terminates it; a proxy-terminated deployment has none.
    properties
        .tls()
        .ifPresent(
            material -> {
              var ssl = new Ssl();
              ssl.setEnabled(true);
              ssl.setKeyStore(material.keyStoreUri().toString());
              ssl.setKeyStorePassword(material.password());
              ssl.setKeyStoreType(material.type());
              if (material.alias() != null) ssl.setKeyAlias(material.alias());
              ssl.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1.3"});
              factory.setSsl(ssl);
            });
    if (!properties.trustedProxies().isEmpty()) {
      // Only the named proxies may rewrite the scheme, so request.isSecure() still means HTTPS.
      var valve = new RemoteIpValve();
      valve.setProtocolHeader("X-Forwarded-Proto");
      valve.setProtocolHeaderHttpsValue("https");
      valve.setRemoteIpHeader("X-Forwarded-For");
      valve.setInternalProxies(properties.trustedProxiesPattern());
      factory.addEngineValves(valve);
    }
    factory.setRegisterDefaultServlet(false);
    return factory;
  }

  @Bean
  DispatcherServlet adminDispatcherServlet() {
    return new DispatcherServlet();
  }

  @Bean
  ServletRegistrationBean<DispatcherServlet> adminDispatcherServletRegistration(
      DispatcherServlet dispatcherServlet) {
    var registration = new ServletRegistrationBean<>(dispatcherServlet, "/");
    registration.setName("dispatcherServlet");
    registration.setLoadOnStartup(1);
    // In-memory only (threshold = limit); used solely by the bounded S2-B upload endpoint.
    long limit = AdminOriginFilter.MAX_UPLOAD_REQUEST_BYTES;
    registration.setMultipartConfig(new MultipartConfigElement("", limit, limit, (int) limit));
    return registration;
  }

  @Bean
  DelegatingFilterProxyRegistrationBean adminSecurityFilterRegistration() {
    var registration = new DelegatingFilterProxyRegistrationBean("springSecurityFilterChain");
    registration.setName("springSecurityFilterChain");
    registration.addUrlPatterns("/*");
    registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
    registration.setAsyncSupported(true);
    registration.setOrder(Integer.MIN_VALUE);
    return registration;
  }
}
