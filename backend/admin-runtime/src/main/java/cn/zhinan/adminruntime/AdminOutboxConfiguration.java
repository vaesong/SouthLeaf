package cn.zhinan.adminruntime;

import cn.zhinan.adminbooking.adapter.jdbc.JdbcBookingAudit;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcCommandLedger;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcConnectionProvider;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcOutboxWorker;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcTransactionRunner;
import cn.zhinan.adminbooking.application.OutboxDispatcher;
import cn.zhinan.adminbooking.application.OutboxOperations;
import cn.zhinan.adminbooking.port.BookingPorts.BookingAudit;
import cn.zhinan.adminbooking.port.BookingPorts.CommandLedger;
import cn.zhinan.adminbooking.port.BookingPorts.NotificationDelivery;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxWorker;
import cn.zhinan.adminbooking.port.BookingPorts.TransactionRunner;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit demo-only outbox composition with a separately verified minimal database identity. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
@Import(AdminOutboxController.class)
class AdminOutboxConfiguration {
  @Bean(destroyMethod = "close")
  VerifiedOutboxDataSource verifiedOutboxDataSource(AdminRuntimeProperties runtime) {
    return VerifiedOutboxDataSource.open(runtime, runtime.outbox());
  }

  @Bean
  JdbcConnectionProvider outboxConnections(VerifiedOutboxDataSource verified) {
    return new JdbcConnectionProvider(verified.dataSource());
  }

  @Bean
  OutboxWorker outboxWorker(@Qualifier("outboxConnections") JdbcConnectionProvider connections) {
    return new JdbcOutboxWorker(connections);
  }

  @Bean
  CommandLedger outboxCommandLedger(
      @Qualifier("outboxConnections") JdbcConnectionProvider connections) {
    return new JdbcCommandLedger(connections);
  }

  @Bean
  BookingAudit outboxAudit(@Qualifier("outboxConnections") JdbcConnectionProvider connections) {
    return new JdbcBookingAudit(connections);
  }

  @Bean
  TransactionRunner outboxTransactions(
      @Qualifier("outboxConnections") JdbcConnectionProvider connections) {
    return new JdbcTransactionRunner(connections);
  }

  @Bean
  NotificationDelivery notificationDelivery() {
    return new TestSinkNotificationDelivery();
  }

  @Bean
  OutboxOperations outboxOperations(
      OutboxWorker worker,
      CommandLedger commands,
      BookingAudit audit,
      TransactionRunner transactions,
      Clock clock) {
    return new OutboxOperations(worker, commands, audit, transactions, clock);
  }

  @Bean
  OutboxDispatcher outboxDispatcher(
      OutboxWorker worker, NotificationDelivery delivery, AdminRuntimeProperties runtime) {
    return new OutboxDispatcher(worker, delivery, runtime.outbox().policy());
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  AdminOutboxScheduler outboxScheduler(
      OutboxDispatcher dispatcher, AdminRuntimeProperties runtime, Clock clock) {
    AdminOutboxProperties properties = runtime.outbox();
    return new AdminOutboxScheduler(
        dispatcher,
        properties.workerId(),
        properties.policy().batchSize(),
        properties.pollIntervalMillis(),
        clock);
  }
}
