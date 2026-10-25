package cn.zhinan.uniapp.authorization.adapter.jdbc;

import cn.zhinan.uniapp.authorization.port.AuthorizationReadResult;
import cn.zhinan.uniapp.authorization.port.PublicOverlayAuthorizationEvaluator;
import cn.zhinan.uniapp.authorization.port.PublicUseContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads the immutable authorization snapshot copied into each V2 per-work release at publication.
 * Demo accepts only TEST_SYNTHETIC; production only VERIFIED_AUTHORITY. Missing/mismatched rows are
 * UNKNOWN/INVALID; database failure is a distinct dependency state (fail-closed 503).
 */
@Repository
public class JdbcWorkOverlayAuthorizationEvaluator implements PublicOverlayAuthorizationEvaluator {
  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate reads;
  private final String mode;

  public JdbcWorkOverlayAuthorizationEvaluator(
      NamedParameterJdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      @Value("${southleaf.public.mode}") String mode) {
    this.jdbc = jdbc;
    this.mode = mode;
    reads = new TransactionTemplate(transactions);
    reads.setReadOnly(true);
    reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  @Override
  public Map<String, AuthorizationReadResult> evaluate(List<PublicUseContext> contexts) {
    if (contexts.isEmpty()) return Map.of();
    if (contexts.size() > 1000)
      throw new IllegalArgumentException("Authorization batch exceeds read-model capacity");
    try {
      return reads.execute(
          status -> {
            var parameters =
                Map.of("ids", contexts.stream().map(PublicUseContext::releaseId).toList());
            var grants =
                jdbc.queryForList(
                    "SELECT a.release_id,a.authority_revision,a.state,a.subject_scope_ref,a.media_scope_sha256,a.purpose,a.channel,a.valid_from,a.valid_until,a.provenance,a.fact_sha256,r.work_id,r.work_version FROM public_work_release_authorization a JOIN public_work_release r ON r.release_id=a.release_id WHERE a.release_id IN (:ids)",
                    parameters);
            var scopes =
                jdbc.queryForList(
                    "SELECT release_id,media_id FROM public_work_release_media WHERE release_id IN (:ids)",
                    parameters);
            Map<String, Map<String, Object>> byRelease = new HashMap<>();
            grants.forEach(row -> byRelease.put((String) row.get("release_id"), row));
            Map<String, Set<String>> media = new HashMap<>();
            scopes.forEach(
                row ->
                    media
                        .computeIfAbsent((String) row.get("release_id"), id -> new HashSet<>())
                        .add((String) row.get("media_id")));
            String expected = "production".equals(mode) ? "VERIFIED_AUTHORITY" : "TEST_SYNTHETIC";
            Map<String, AuthorizationReadResult> results = new HashMap<>();
            for (var requested : contexts) {
              var row = byRelease.get(requested.releaseId());
              Set<String> covered = media.getOrDefault(requested.releaseId(), Set.of());
              if (row == null
                  || !expected.equals(row.get("provenance"))
                  || !requested.workId().equals(row.get("work_id"))
                  || !digest(covered).equals(row.get("media_scope_sha256"))) {
                results.put(requested.workId(), unknown());
                continue;
              }
              var context =
                  new PublicUseContext(
                      (String) row.get("work_id"),
                      (String) row.get("release_id"),
                      String.valueOf(((Number) row.get("work_version")).intValue()),
                      (String) row.get("release_id"),
                      covered,
                      (String) row.get("subject_scope_ref"),
                      (String) row.get("purpose"),
                      (String) row.get("channel"),
                      requested.evaluatedAt());
              var outcome =
                  "VALID".equals(row.get("state"))
                      ? AuthorizationReadResult.Outcome.VALID
                      : AuthorizationReadResult.Outcome.INVALID;
              results.put(
                  requested.workId(),
                  new AuthorizationReadResult.Decision(
                      outcome,
                      context,
                      ((LocalDateTime) row.get("valid_from")).toInstant(ZoneOffset.UTC),
                      ((LocalDateTime) row.get("valid_until")).toInstant(ZoneOffset.UTC),
                      (String) row.get("authority_revision"),
                      (String) row.get("fact_sha256")));
            }
            return Map.copyOf(results);
          });
    } catch (DataAccessException | TransactionException ex) {
      Map<String, AuthorizationReadResult> results = new HashMap<>();
      contexts.forEach(
          c ->
              results.put(
                  c.workId(),
                  new AuthorizationReadResult.DependencyUnavailable(
                      "WORK_OVERLAY_AUTHORITY_UNAVAILABLE")));
      return Map.copyOf(results);
    }
  }

  /** Same canonical media-scope digest as the management publication writer. */
  static String digest(Set<String> mediaIds) {
    try {
      byte[] bytes =
          String.join("\n", mediaIds.stream().sorted().toList()).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static AuthorizationReadResult unknown() {
    return new AuthorizationReadResult.Decision(
        AuthorizationReadResult.Outcome.UNKNOWN, null, null, null, null, null);
  }
}
