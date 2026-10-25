package cn.zhinan.adminsecurity.adapter.jdbc;

import cn.zhinan.adminsecurity.domain.AdminAccount;
import cn.zhinan.adminsecurity.domain.CredentialSnapshot;
import cn.zhinan.adminsecurity.port.AdminSecurityStorageException;
import cn.zhinan.adminsecurity.port.CredentialLookup;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** No credential/version cache, locking read, normalization or technical-failure-as-absence. */
public final class JdbcCredentialLookup implements CredentialLookup {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate read;

  public JdbcCredentialLookup(DataSource source) {
    Objects.requireNonNull(source);
    jdbc = new JdbcTemplate(source);
    read = AdminJdbcTransactions.independent(source, true);
  }

  @Override
  public Optional<CredentialSnapshot> find(String accountId) {
    AdminAccount.requireAccountId(accountId);
    try {
      return Objects.requireNonNull(
          read.execute(
              status -> {
                var rows =
                    jdbc.query(
                        "SELECT account_id,enabled,security_version,credential_hash FROM admin_account WHERE account_id=?",
                        (row, index) ->
                            new CredentialSnapshot(
                                row.getString("account_id"),
                                row.getBoolean("enabled"),
                                row.getLong("security_version"),
                                row.getString("credential_hash")),
                        accountId);
                if (rows.size() > 1)
                  throw new IllegalArgumentException("Ambiguous credential fact");
                return rows.stream().findFirst();
              }));
    } catch (DataAccessException | TransactionException | IllegalArgumentException ex) {
      throw new AdminSecurityStorageException(ex);
    }
  }
}
