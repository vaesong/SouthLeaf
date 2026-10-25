package cn.zhinan.uniapp.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.zhinan.uniapp.publicquery.adapter.jdbc.JdbcPublishedCatalogRepository;
import cn.zhinan.uniapp.publicquery.port.CatalogStorageException;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * This suite requires a real, explicitly named, isolated MySQL database. No H2 or skipped fallback.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DatabasePublicApiIT {
  // Mutable isolated fixtures use the test writer. The real runtime guard is separately exercised
  // against both actual Reader and writer connections below; no production bypass flag exists.
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  cn.zhinan.uniapp.config.RuntimeAccountVerifier startupAccount;

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Required integration environment: " + name);
    return value;
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    String url = required("SOUTHLEAF_TEST_DB_URL");
    boolean originalFixture = url.startsWith("jdbc:mysql://127.0.0.1:3306/southleaf_public_it?");
    boolean explicitlyOwnedFixture =
        "owned".equals(System.getenv("SOUTHLEAF_PUBLIC_IT_ISOLATED"))
            && url.matches(
                "jdbc:mysql://localhost:([1-9][0-9]{3,4})/southleaf_public_it[?]sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    if (!originalFixture && !explicitlyOwnedFixture)
      throw new IllegalStateException(
          "Only an explicit isolated public integration schema may be modified");
    properties.add("spring.datasource.url", () -> url);
    properties.add("spring.datasource.username", () -> required("SOUTHLEAF_TEST_DB_USERNAME"));
    properties.add("spring.datasource.password", () -> required("SOUTHLEAF_TEST_DB_PASSWORD"));
    properties.add("southleaf.public.mode", () -> "demo");
  }

  private DriverManagerDataSource testDataSource() {
    return new DriverManagerDataSource(
        required("SOUTHLEAF_TEST_DB_URL"),
        required("SOUTHLEAF_TEST_DB_USERNAME"),
        required("SOUTHLEAF_TEST_DB_PASSWORD"));
  }

  private JdbcTemplate writer() {
    return new JdbcTemplate(testDataSource());
  }

  private String release() {
    return writer()
        .queryForObject(
            "SELECT release_id FROM active_publication WHERE singleton_id=1", String.class);
  }

  private void grant(String assignment, Object... args) {
    writer()
        .update(
            "UPDATE public_authorization SET " + assignment + " WHERE work_id='demo-work-child'",
            args);
  }

  @Test
  void actualRuntimeReaderPassesAccountGuard() {
    var ds =
        new DriverManagerDataSource(
            required("SOUTHLEAF_DB_URL"),
            required("SOUTHLEAF_DB_USERNAME"),
            required("SOUTHLEAF_DB_PASSWORD"));
    assertThatCode(() -> new cn.zhinan.uniapp.config.RuntimeAccountVerifier(ds, "demo").verify())
        .doesNotThrowAnyException();
  }

  @Test
  void actualIntegrationWriterCannotBeUsedAsRuntimeIdentity() {
    assertThatThrownBy(
            () ->
                new cn.zhinan.uniapp.config.RuntimeAccountVerifier(testDataSource(), "demo")
                    .verify())
        .hasMessageContaining("SELECT-only");
  }

  @Test
  void anotherPublicationCannotReuseOldScopedGrantEvenWhenAllWorkVersionsAreReused()
      throws Exception {
    var jdbc = writer();
    var original = release();
    var next = "release-boundary-test";
    var tables =
        List.of(
            "media_asset",
            "media_variant",
            "studio_brand",
            "studio_contact",
            "team_member",
            "team_specialty",
            "contact_channel",
            "photography_business",
            "catalog_package",
            "package_line",
            "public_work",
            "work_business",
            "work_media",
            "home_featured_business",
            "home_featured_package");
    try {
      jdbc.update(
          "INSERT INTO catalog_release(id,content_version,data_mode,approval_status) VALUES(?,?,'demo','DEMO_ONLY')",
          next,
          next);
      for (var table : tables) {
        var columns =
            jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ORDINAL_POSITION",
                String.class,
                table);
        assertThat(columns.getFirst()).isEqualTo("release_id");
        var rest = String.join(",", columns.subList(1, columns.size()));
        jdbc.update(
            "INSERT INTO " + table + " SELECT ?," + rest + " FROM " + table + " WHERE release_id=?",
            next,
            original);
      }
      jdbc.update("UPDATE active_publication SET release_id=? WHERE singleton_id=1", next);
      mvc.perform(get("/api/v1/public/works"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.items.length()").value(0));
      mvc.perform(get("/api/v1/public/works/demo-work-child")).andExpect(status().isNotFound());
      mvc.perform(get("/api/v1/public/media/work-child/preview")).andExpect(status().isNotFound());
    } finally {
      jdbc.update("UPDATE active_publication SET release_id=? WHERE singleton_id=1", original);
      for (int n = tables.size() - 1; n >= 0; n--)
        jdbc.update("DELETE FROM " + tables.get(n) + " WHERE release_id=?", next);
      jdbc.update("DELETE FROM catalog_release WHERE id=?", next);
    }
  }

  @Test
  void scopedFactsForSixWorksUseExactlyTwoDatabaseStatements() {
    var ds = testDataSource();
    var jdbc = spy(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(ds));
    var evaluator =
        new cn.zhinan.uniapp.authorization.adapter.jdbc.JdbcPublicAuthorizationEvaluator(
            jdbc, new DataSourceTransactionManager(ds), "demo");
    var contexts =
        writer().queryForList("SELECT work_id FROM public_authorization", String.class).stream()
            .map(
                id ->
                    new cn.zhinan.uniapp.authorization.port.PublicUseContext(
                        id,
                        release(),
                        "test-candidate",
                        "test-binding",
                        Set.of("test-media"),
                        "test-subject",
                        "PUBLIC_WORK_DISPLAY",
                        "WECHAT_MINI_PROGRAM",
                        java.time.Instant.now()))
            .toList();
    assertThat(contexts).hasSize(6);
    assertThat(evaluator.evaluate(contexts)).hasSize(6);
    verify(jdbc, times(2))
        .queryForList(anyString(), org.mockito.ArgumentMatchers.<String, Object>anyMap());
  }

  @Test
  void realDatabaseContainsPublishedEntitiesAndPreparedVariants() {
    var jdbc = writer();
    assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
        .isEqualTo("southleaf_public_it");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photography_business", Integer.class))
        .isEqualTo(6);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public_work", Integer.class)).isEqualTo(6);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_package", Integer.class))
        .isEqualTo(6);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_variant", Integer.class))
        .isEqualTo(70);
  }

  @Test
  void homeAndMetadataComeFromTheSameDatabasePublication() throws Exception {
    mvc.perform(get("/api/v1/public/home"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.businesses.length()").value(3))
        .andExpect(jsonPath("$.data.packages.length()").value(0))
        .andExpect(jsonPath("$.data.brand.englishName").value("SOUTHLEAF STUDIO"))
        .andExpect(
            jsonPath("$.meta.contentVersion")
                .value(
                    writer()
                        .queryForObject(
                            "SELECT content_version FROM catalog_release WHERE id=?",
                            String.class,
                            release())));
  }

  @Test
  void newRepositoryAndConnectionReadPersistentState() {
    var ds = testDataSource();
    var repository =
        new JdbcPublishedCatalogRepository(
            new JdbcTemplate(ds), new DataSourceTransactionManager(ds));
    assertThat(repository.readCurrent().works()).hasSize(6);
    assertThat(repository.readCurrent().contact().channels()).hasSize(3);
  }

  @Test
  void schemaValidationAndRepeatMigrationAreClean() {
    var flyway =
        Flyway.configure().dataSource(testDataSource()).locations("classpath:db/migration").load();
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(
            writer()
                .queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0", Integer.class))
        .isZero();
  }

  @Test
  void schemaRejectsBrokenBusinessRelations() {
    assertThatThrownBy(
            () ->
                writer()
                    .update(
                        "INSERT INTO work_business(release_id,work_id,business_id,position) VALUES(?,?,?,?)",
                        release(),
                        "missing-work",
                        "family",
                        99))
        .hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class);
  }

  @Test
  void runtimeReaderHasNoWritePermission() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                required("SOUTHLEAF_DB_URL"),
                required("SOUTHLEAF_DB_USERNAME"),
                required("SOUTHLEAF_DB_PASSWORD"));
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () -> statement.executeUpdate("UPDATE studio_brand SET name=name WHERE 1=0"))
          .isInstanceOf(SQLException.class)
          .satisfies(ex -> assertThat(((SQLException) ex).getErrorCode()).isEqualTo(1142));
    }
  }

  @Test
  void databaseConnectionUsesTls() throws Exception {
    try (var connection = testDataSource().getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery("SHOW STATUS LIKE 'Ssl_cipher'")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getString(2)).isNotBlank();
    }
  }

  @Test
  void revokedGrantImmediatelyBlocksDetailListsGetHeadAndRange() throws Exception {
    grant("state='REVOKED',authority_revision='it-revoked'");
    try {
      mvc.perform(get("/api/v1/public/works/demo-work-child")).andExpect(status().isNotFound());
      mvc.perform(get("/api/v1/public/works"))
          .andExpect(jsonPath("$.data.items.length()").value(5));
      mvc.perform(get("/api/v1/public/media/work-child/preview/content"))
          .andExpect(status().isNotFound());
      mvc.perform(head("/api/v1/public/media/work-child/preview/content"))
          .andExpect(status().isNotFound());
      mvc.perform(
              get("/api/v1/public/media/work-child/preview/content").header("Range", "bytes=0-10"))
          .andExpect(status().isNotFound());
    } finally {
      grant("state='VALID',authority_revision='demo-authority-v1'");
    }
  }

  @Test
  void candidateBindingMismatchIsNotAuthorized() throws Exception {
    grant("binding_version='wrong-binding'");
    try {
      mvc.perform(get("/api/v1/public/works/demo-work-child")).andExpect(status().isNotFound());
    } finally {
      grant("binding_version='media-v1'");
    }
  }

  @Test
  void expiredAuthorizationIsNotPublic() throws Exception {
    grant("valid_until='2026-02-01 00:00:00'");
    try {
      mvc.perform(get("/api/v1/public/works/demo-work-child")).andExpect(status().isNotFound());
    } finally {
      grant("valid_until='2099-01-01 00:00:00'");
    }
  }

  @Test
  void incompleteMediaScopeIsNotPublic() throws Exception {
    writer()
        .update(
            "DELETE FROM authorization_media WHERE work_id='demo-work-child' AND media_id='work-child-moment'");
    try {
      mvc.perform(get("/api/v1/public/works/demo-work-child")).andExpect(status().isNotFound());
    } finally {
      writer()
          .update(
              "INSERT INTO authorization_media(work_id,release_id,media_id) VALUES(?,?,?)",
              "demo-work-child",
              release(),
              "work-child-moment");
    }
  }

  @Test
  void wrongProvenanceCannotCrossDemoBoundary() throws Exception {
    grant("provenance='VERIFIED_AUTHORITY'");
    try {
      mvc.perform(get("/api/v1/public/works/demo-work-child")).andExpect(status().isNotFound());
    } finally {
      grant("provenance='DEMO_GENERATED'");
    }
  }

  @Test
  void preparedMediaSupportsHeadNoStoreAndRejectsRanges() throws Exception {
    String path = "/api/v1/public/media/work-child/preview/content";
    mvc.perform(get(path).header("If-None-Match", "anything"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().contentType("image/png"));
    mvc.perform(head(path)).andExpect(status().isOk());
    mvc.perform(get(path).header("Range", "bytes=0-10"))
        .andExpect(status().isRequestedRangeNotSatisfiable());
  }

  @Test
  void onlyTheNewApiIsRegistered() throws Exception {
    for (String prefix : List.of("/api/categories", "/api/packages")) {
      mvc.perform(post(prefix + "/add").contentType("application/json").content("{}"))
          .andExpect(status().isNotFound());
      mvc.perform(patch(prefix + "/update/test").contentType("application/json").content("{}"))
          .andExpect(status().isNotFound());
      mvc.perform(delete(prefix + "/delete?id=test")).andExpect(status().isNotFound());
    }
    mvc.perform(get("/api/swipper/getSwipperImages")).andExpect(status().isNotFound());
    mvc.perform(get("/public-data/catalog.json")).andExpect(status().isNotFound());
  }

  @Test
  void invalidQueriesAndUnknownRoutesHaveStableErrors() throws Exception {
    mvc.perform(get("/api/v1/public/works?limit=0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PUBLIC_QUERY_INVALID"));
    mvc.perform(get("/api/v1/public/works?sort=unknown")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/public/not-found")).andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/public/home"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().exists("Allow"));
  }

  @Test
  void storageFailureNeverReturnsTheCachedCatalog() {
    var ds = testDataSource();
    var jdbc = spy(new JdbcTemplate(ds));
    var repository = new JdbcPublishedCatalogRepository(jdbc, new DataSourceTransactionManager(ds));
    assertThat(repository.readCurrent().works()).hasSize(6);
    doThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "simulated read failure"))
        .when(jdbc)
        .queryForMap(anyString());
    assertThatThrownBy(repository::readCurrent).isInstanceOf(CatalogStorageException.class);
  }

  @Test
  void atomicPublicationSwitchInvalidatesCursorAndRefreshesBrand() throws Exception {
    String original = release();
    String next = "it-" + UUID.randomUUID();
    String version = "db-" + UUID.randomUUID();
    String response =
        mvc.perform(get("/api/v1/public/works?limit=1&sort=newest"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String cursor = mapper.readTree(response).path("meta").path("nextCursor").asText();
    List<String> tables =
        List.of(
            "media_asset",
            "media_variant",
            "studio_brand",
            "studio_contact",
            "team_member",
            "team_specialty",
            "contact_channel",
            "photography_business",
            "catalog_package",
            "package_line",
            "public_work",
            "work_business",
            "work_media",
            "home_featured_business",
            "home_featured_package");
    try {
      try (var connection = testDataSource().getConnection()) {
        connection.setAutoCommit(false);
        try {
          try (var insert =
              connection.prepareStatement(
                  "INSERT INTO catalog_release(id,content_version,data_mode,approval_status) VALUES(?,?,'demo','DEMO_ONLY')")) {
            insert.setString(1, next);
            insert.setString(2, version);
            insert.executeUpdate();
          }
          for (String table : tables) {
            var columns =
                writer()
                    .queryForList(
                        "SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",
                        String.class,
                        table);
            String all =
                columns.stream()
                    .map(c -> "`" + c + "`")
                    .collect(java.util.stream.Collectors.joining(","));
            String rest =
                columns.stream()
                    .filter(c -> !c.equals("release_id"))
                    .map(c -> "`" + c + "`")
                    .collect(java.util.stream.Collectors.joining(","));
            try (var copy =
                connection.prepareStatement(
                    "INSERT INTO "
                        + table
                        + "("
                        + all
                        + ") SELECT ?,"
                        + rest
                        + " FROM "
                        + table
                        + " WHERE release_id=?")) {
              copy.setString(1, next);
              copy.setString(2, original);
              copy.executeUpdate();
            }
          }
          try (var update =
              connection.prepareStatement(
                  "UPDATE studio_brand SET name='Integration publication' WHERE release_id=?")) {
            update.setString(1, next);
            update.executeUpdate();
          }
          try (var activate =
              connection.prepareStatement(
                  "UPDATE active_publication SET release_id=? WHERE singleton_id=1")) {
            activate.setString(1, next);
            activate.executeUpdate();
          }
          connection.commit();
        } catch (Exception ex) {
          connection.rollback();
          throw ex;
        }
      }
      mvc.perform(get("/api/v1/public/brand"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.name").value("Integration publication"))
          .andExpect(jsonPath("$.meta.contentVersion").value(version));
      mvc.perform(
              get("/api/v1/public/works")
                  .param("limit", "1")
                  .param("sort", "newest")
                  .param("cursor", cursor))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("PUBLIC_CURSOR_EXPIRED"));
    } finally {
      writer().update("UPDATE active_publication SET release_id=? WHERE singleton_id=1", original);
      List<String> reversed = new ArrayList<>(tables);
      Collections.reverse(reversed);
      for (String table : reversed)
        writer().update("DELETE FROM " + table + " WHERE release_id=?", next);
      writer().update("DELETE FROM catalog_release WHERE id=?", next);
    }
  }
}
