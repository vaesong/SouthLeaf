package cn.zhinan.uniapp.booking.config;

import cn.zhinan.uniapp.booking.application.PublicBookingService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the customer entry; a missing or partial environment disables it instead of guessing. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
public class PublicBookingConfiguration {
  @Bean
  public PublicBookingProperties publicBookingProperties(
      @Value("${spring.datasource.username:}") String publicReaderUser) {
    return PublicBookingProperties.load(System.getenv(), publicReaderUser);
  }

  @Bean(destroyMethod = "close")
  public PublicBookingRuntime publicBookingRuntime(
      PublicBookingProperties properties, Clock clock) {
    return PublicBookingRuntime.open(properties, clock);
  }

  @Bean
  public PublicBookingService publicBookingService(PublicBookingRuntime runtime) {
    return runtime.service();
  }
}
