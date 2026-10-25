package cn.zhinan.uniapp.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/** Runtime validates identity and schema; it never creates or repairs either. */
@Component
public class PublishedSchemaVerifier implements InitializingBean {
  private final DataSource dataSource;
  private final RuntimeAccountVerifier account;

  public PublishedSchemaVerifier(DataSource dataSource, RuntimeAccountVerifier account) {
    this.dataSource = dataSource;
    this.account = account;
  }

  @Override
  public void afterPropertiesSet() {
    account.verify();
    var flyway =
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
    flyway.validate();
    if (flyway.info().current() == null)
      throw new IllegalStateException(
          "Explicit database migration must run before application startup");
  }
}
