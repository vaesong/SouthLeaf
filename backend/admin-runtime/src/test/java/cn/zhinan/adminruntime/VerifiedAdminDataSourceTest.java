package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class VerifiedAdminDataSourceTest {
  @Test
  void onlyVerifiedPoolBuildsStoreAndBothTransactionsUseSameSource() throws Exception {
    var pool = new MetadataPoolFixture();
    var verified = VerifiedAdminDataSource.open(AdminRuntimePropertiesTest.properties(), p -> pool);
    assertThat(pool.queries)
        .containsExactly(
            AdminDatabaseVerifier.IDENTITY,
            AdminDatabaseVerifier.TLS,
            AdminDatabaseVerifier.GRANTS,
            AdminDatabaseVerifier.AUTH_EVENT_SHAPE);
    assertThat(pool.opened).isEqualTo(1);
    assertThat(pool.connectionsClosed).isEqualTo(1);
    assertThat(pool.statementsClosed).isEqualTo(1);
    assertThat(pool.resultSetsClosed).isEqualTo(4);
    assertThat(pool.queryTimeout).isEqualTo(5);
    assertThat(pool.poolsClosed).isZero();
    var store = verified.sessionStore();
    assertThat(verified.sessionStore()).isSameAs(store);
    assertThat(((JdbcTemplate) field(store, "jdbc")).getDataSource()).isSameAs(pool.dataSource);
    for (String name : List.of("write", "read"))
      assertThat(
              ((DataSourceTransactionManager)
                      ((TransactionTemplate) field(store, name)).getTransactionManager())
                  .getDataSource())
          .isSameAs(pool.dataSource);
    for (Object adapter : List.of(verified.credentialLookup(), verified.authenticationEvents()))
      assertThat(((JdbcTemplate) field(adapter, "jdbc")).getDataSource()).isSameAs(pool.dataSource);
    for (var pair :
        List.of(
            new Object[] {verified.credentialLookup(), "read"},
            new Object[] {verified.authenticationEvents(), "write"}))
      assertThat(
              ((DataSourceTransactionManager)
                      ((TransactionTemplate) field(pair[0], (String) pair[1]))
                          .getTransactionManager())
                  .getDataSource())
          .isSameAs(pool.dataSource);
    verified.close();
    verified.close();
    assertThat(pool.poolsClosed).isEqualTo(1);
  }

  @Test
  void realOwnedPoolIsLazyAndUsesFixedBudgets() {
    var pool = VerifiedAdminDataSource.createPool(AdminRuntimePropertiesTest.properties());
    var source = (com.zaxxer.hikari.HikariDataSource) pool.source();
    try {
      assertThat(source.isRunning()).isFalse();
      assertThat(source.getMaximumPoolSize()).isEqualTo(4);
      assertThat(source.getMinimumIdle()).isZero();
      assertThat(source.getConnectionTimeout()).isEqualTo(5000);
      assertThat(source.getValidationTimeout()).isEqualTo(2000);
      assertThat(source.isRegisterMbeans()).isFalse();
      assertThat(source.getDataSourceProperties().getProperty("logger"))
          .isEqualTo("com.mysql.cj.log.Slf4JLogger");
    } finally {
      pool.close();
    }
    assertThat(source.isClosed()).isTrue();
  }

  @Test
  void everySqlFailureClosesOwnedPoolAndHidesRawFailure() {
    for (String sql :
        List.of(
            "connection",
            AdminDatabaseVerifier.IDENTITY,
            AdminDatabaseVerifier.TLS,
            AdminDatabaseVerifier.GRANTS,
            AdminDatabaseVerifier.AUTH_EVENT_SHAPE)) {
      var pool = new MetadataPoolFixture();
      pool.failOn = sql;
      assertThatThrownBy(
              () ->
                  VerifiedAdminDataSource.open(AdminRuntimePropertiesTest.properties(), p -> pool))
          .hasMessage("ADMIN_RUNTIME_REFUSED:DATABASE_CHECK")
          .hasNoCause();
      assertThat(pool.poolsClosed).isEqualTo(1);
      assertThat(pool.connectionsClosed).isEqualTo(sql.equals("connection") ? 0 : 1);
    }
  }

  @Test
  void identityRequiresExactlyOneCompleteMatchingRow() {
    for (int column : new int[] {0, 1})
      for (String value :
          new String[] {null, "", "southleaf_public", "root@localhost", "fixture_writer@%"}) {
        int c = column;
        reject(p -> p.rows.get(AdminDatabaseVerifier.IDENTITY).getFirst()[c] = value, "IDENTITY");
      }
    reject(p -> p.rows.get(AdminDatabaseVerifier.IDENTITY).clear(), "IDENTITY");
    reject(
        p ->
            p.rows
                .get(AdminDatabaseVerifier.IDENTITY)
                .add(p.rows.get(AdminDatabaseVerifier.IDENTITY).getFirst()),
        "IDENTITY");
  }

  @Test
  void activeOrMandatoryRolesAndUnknownRoleMetadataFailClosed() {
    for (String role : new String[] {null, "", "none", "`reader`@`%`"})
      reject(p -> p.rows.get(AdminDatabaseVerifier.IDENTITY).getFirst()[2] = role, "ROLES");
    for (String role : new String[] {null, "`mandatory`@`%`", "NONE"})
      reject(p -> p.rows.get(AdminDatabaseVerifier.IDENTITY).getFirst()[3] = role, "ROLES");
  }

  @Test
  void tlsMustBeNonemptyAndUnambiguous() {
    for (String cipher : new String[] {null, "", " "})
      reject(p -> p.rows.get(AdminDatabaseVerifier.TLS).getFirst()[1] = cipher, "TLS");
    reject(p -> p.rows.get(AdminDatabaseVerifier.TLS).clear(), "TLS");
    reject(p -> p.rows.get(AdminDatabaseVerifier.TLS).getFirst()[0] = "wrong_status", "TLS");
    reject(
        p ->
            p.rows
                .get(AdminDatabaseVerifier.TLS)
                .add(p.rows.get(AdminDatabaseVerifier.TLS).getFirst()),
        "TLS");
  }

  @Test
  void invalidAndOversizedGrantsDoNotPublishCapability() {
    reject(p -> p.rows.get(AdminDatabaseVerifier.GRANTS).removeLast(), "GRANTS");
    reject(
        p ->
            p.rows
                .get(AdminDatabaseVerifier.GRANTS)
                .add(new String[] {"GRANT `role_reader`@`%` TO `fixture_writer`@`localhost`"}),
        "GRANTS");
    reject(
        p -> {
          var many = new ArrayList<String[]>();
          for (int n = 0; n < 65; n++)
            many.add(new String[] {"GRANT USAGE ON *.* TO `fixture_writer`@`localhost`"});
          p.rows.put(AdminDatabaseVerifier.GRANTS, many);
        },
        "GRANTS");
    reject(
        p -> p.rows.get(AdminDatabaseVerifier.GRANTS).getFirst()[0] = "x".repeat(4097),
        "DATABASE_CHECK");
  }

  @Test
  void missingExtraOrIncompatibleAuthEventColumnsFailBeforePublication() {
    reject(p -> p.rows.get(AdminDatabaseVerifier.AUTH_EVENT_SHAPE).clear(), "DATABASE_CHECK");
    reject(p -> p.rows.get(AdminDatabaseVerifier.AUTH_EVENT_SHAPE).removeLast(), "DATABASE_CHECK");
    reject(
        p ->
            p.rows
                .get(AdminDatabaseVerifier.AUTH_EVENT_SHAPE)
                .add(new String[] {"extra", "text", "YES"}),
        "DATABASE_CHECK");
    for (int column = 0; column < 3; column++) {
      int index = column;
      reject(
          p -> p.rows.get(AdminDatabaseVerifier.AUTH_EVENT_SHAPE).getFirst()[index] = "wrong",
          "DATABASE_CHECK");
      reject(
          p -> p.rows.get(AdminDatabaseVerifier.AUTH_EVENT_SHAPE).getFirst()[index] = null,
          "DATABASE_CHECK");
    }
  }

  private static void reject(Consumer<MetadataPoolFixture> change, String reason) {
    var pool = new MetadataPoolFixture();
    change.accept(pool);
    assertThatThrownBy(
            () -> VerifiedAdminDataSource.open(AdminRuntimePropertiesTest.properties(), p -> pool))
        .hasMessage("ADMIN_RUNTIME_REFUSED:" + reason)
        .hasNoCause();
    assertThat(pool.poolsClosed).isEqualTo(1);
    assertThat(pool.connectionsClosed).isEqualTo(1);
    assertThat(pool.statementsClosed).isEqualTo(1);
  }

  private static Object field(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }
}
