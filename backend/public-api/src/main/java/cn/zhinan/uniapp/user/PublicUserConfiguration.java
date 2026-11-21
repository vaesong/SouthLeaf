package cn.zhinan.uniapp.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires mini-program login independently of the booking switch. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
public class PublicUserConfiguration {
  @Bean
  public PublicUserProperties publicUserProperties(
      @Value("${spring.datasource.username:}") String publicReaderUser) {
    return PublicUserProperties.load(System.getenv(), publicReaderUser);
  }

  @Bean(destroyMethod = "close")
  public PublicUserRuntime publicUserRuntime(PublicUserProperties properties) {
    return PublicUserRuntime.open(properties);
  }
}
