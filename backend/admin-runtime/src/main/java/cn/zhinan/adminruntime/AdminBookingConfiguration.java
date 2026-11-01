package cn.zhinan.adminruntime;

import cn.zhinan.adminbooking.adapter.jdbc.JdbcBookingAudit;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcCommandLedger;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcConnectionProvider;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcCustomerRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcOperatingRuleRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcOrderRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcPackageFactCatalog;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcReservationRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcScheduleRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcSlotGuard;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcTransactionRunner;
import cn.zhinan.adminbooking.application.BookingApplication;
import cn.zhinan.adminbooking.application.OperatingRuleService;
import cn.zhinan.adminbooking.application.SlotOpeningService;
import cn.zhinan.adminbooking.port.BookingPorts.PackageCatalog;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit local booking composition over a separately verified booking-writer identity. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
@Import(AdminBookingController.class)
class AdminBookingConfiguration {
  @Bean(destroyMethod = "close")
  VerifiedBookingDataSource verifiedBookingDataSource(AdminRuntimeProperties runtime) {
    return VerifiedBookingDataSource.open(runtime, runtime.booking());
  }

  @Bean
  JdbcConnectionProvider bookingConnections(VerifiedBookingDataSource verified) {
    return new JdbcConnectionProvider(verified.dataSource());
  }

  @Bean
  OperatingRuleService operatingRuleService(
      @Qualifier("bookingConnections") JdbcConnectionProvider connections, Clock clock) {
    return new OperatingRuleService(
        new JdbcOperatingRuleRepository(connections),
        new JdbcCommandLedger(connections),
        new JdbcTransactionRunner(connections),
        clock);
  }

  @Bean
  SlotOpeningService slotOpeningService(
      @Qualifier("bookingConnections") JdbcConnectionProvider connections, Clock clock) {
    var audit = new JdbcBookingAudit(connections);
    return new SlotOpeningService(
        new JdbcOperatingRuleRepository(connections),
        new JdbcScheduleRepository(connections),
        new JdbcSlotGuard(connections),
        new JdbcCommandLedger(connections),
        audit,
        new JdbcTransactionRunner(connections),
        clock);
  }

  @Bean
  BookingApplication bookingApplication(
      @Qualifier("bookingConnections") JdbcConnectionProvider connections,
      AdminRuntimeProperties runtime,
      Clock clock) {
    String dataMode = runtime.booking().dataMode();
    var customers = new JdbcCustomerRepository(connections);
    var schedule = new JdbcScheduleRepository(connections);
    var reservations = new JdbcReservationRepository(connections);
    var orders = new JdbcOrderRepository(connections);
    var commands = new JdbcCommandLedger(connections);
    var auditAndOutbox = new JdbcBookingAudit(connections);
    var transactions = new JdbcTransactionRunner(connections);
    // Package facts are read from the immutable admin_package_fact table through a mode-scoped
    // read-only adapter: a PRODUCTION runtime cannot see SYNTHETIC rows and vice versa.
    PackageCatalog packages = new JdbcPackageFactCatalog(connections, dataMode);
    return new BookingApplication(
        customers,
        schedule,
        reservations,
        orders,
        packages,
        commands,
        auditAndOutbox,
        auditAndOutbox,
        transactions,
        clock);
  }
}
