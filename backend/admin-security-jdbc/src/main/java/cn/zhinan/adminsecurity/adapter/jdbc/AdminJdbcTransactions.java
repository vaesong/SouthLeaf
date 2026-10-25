package cn.zhinan.adminsecurity.adapter.jdbc;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fresh primary-store facts and independently committed authentication events; never ambient work.
 */
final class AdminJdbcTransactions {
  private AdminJdbcTransactions() {}

  static TransactionTemplate independent(DataSource source, boolean readOnly) {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setReadOnly(readOnly);
    tx.setTimeout(5);
    return tx;
  }
}
