package cn.zhinan.adminruntime;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.EnumSet;
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
  ServletWebServerFactory adminServletWebServerFactory(AdminWebProperties properties)
      throws UnknownHostException {
    if (!properties.enabled())
      throw new AdminStartupException(AdminStartupException.Reason.CONFIGURATION);
    var factory = new TomcatServletWebServerFactory();
    factory.setAddress(InetAddress.getByName("localhost"));
    factory.setPort(properties.port());
    var ssl = new Ssl();
    ssl.setEnabled(true);
    ssl.setKeyStore(properties.keyStorePath().toUri().toString());
    ssl.setKeyStorePassword(properties.keyStorePassword());
    ssl.setKeyStoreType(properties.keyStoreType());
    ssl.setKeyAlias("localhost");
    ssl.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1.3"});
    factory.setSsl(ssl);
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
