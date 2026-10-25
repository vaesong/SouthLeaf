package cn.zhinan.admincontent.adapter.jdbc;

import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared JDBC plumbing for one verified identity. All storage failures become one category. */
final class ContentJdbc {
  final JdbcTemplate jdbc;
  final NamedParameterJdbcTemplate named;
  private final TransactionTemplate writes;
  private final TransactionTemplate reads;

  ContentJdbc(DataSource source) {
    this.jdbc = new JdbcTemplate(source);
    this.jdbc.setQueryTimeout(10);
    this.named = new NamedParameterJdbcTemplate(jdbc);
    var transactions = new DataSourceTransactionManager(source);
    this.writes = new TransactionTemplate(transactions);
    writes.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    writes.setTimeout(15);
    this.reads = new TransactionTemplate(transactions);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    reads.setReadOnly(true);
    reads.setTimeout(10);
  }

  <T> T write(Supplier<T> work) {
    try {
      return writes.execute(status -> work.get());
    } catch (DuplicateKeyException race) {
      throw race; // callers own idempotency-race handling after the rollback
    } catch (DataAccessException | TransactionException ex) {
      throw storage(ex);
    }
  }

  <T> T read(Supplier<T> work) {
    try {
      return reads.execute(status -> work.get());
    } catch (DataAccessException | TransactionException ex) {
      throw storage(ex);
    }
  }

  static ContentException storage(RuntimeException ignored) {
    return new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
  }

  static Timestamp ts(Instant instant) {
    return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
  }

  static Instant instant(Object value) {
    if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
    if (value instanceof Timestamp timestamp)
      return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "STORAGE_TYPE");
  }
}
