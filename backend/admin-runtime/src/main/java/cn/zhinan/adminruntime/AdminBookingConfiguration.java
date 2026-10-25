package cn.zhinan.adminruntime;

import cn.zhinan.adminbooking.adapter.jdbc.JdbcBookingAudit;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcCommandLedger;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcConnectionProvider;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcCustomerRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcOrderRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcReservationRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcScheduleRepository;
import cn.zhinan.adminbooking.adapter.jdbc.JdbcTransactionRunner;
import cn.zhinan.adminbooking.application.BookingApplication;
import cn.zhinan.adminbooking.port.BookingPorts.PackageCatalog;
import java.time.Clock;
import java.util.Optional;
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
  BookingApplication bookingApplication(
      @Qualifier("bookingConnections") JdbcConnectionProvider connections, Clock clock) {
    var customers = new JdbcCustomerRepository(connections);
    var schedule = new JdbcScheduleRepository(connections);
    var reservations = new JdbcReservationRepository(connections);
    var orders = new JdbcOrderRepository(connections);
    var commands = new JdbcCommandLedger(connections);
    var auditAndOutbox = new JdbcBookingAudit(connections);
    var transactions = new JdbcTransactionRunner(connections);
    // Package facts are intentionally unavailable in this local operations slice. No create or
    // confirm endpoint is exposed until the immutable published-package adapter is wired.
    PackageCatalog unavailablePackages = packageVersionRef -> Optional.empty();
    return new BookingApplication(
        customers,
        schedule,
        reservations,
        orders,
        unavailablePackages,
        commands,
        auditAndOutbox,
        auditAndOutbox,
        transactions,
        clock);
  }
}
