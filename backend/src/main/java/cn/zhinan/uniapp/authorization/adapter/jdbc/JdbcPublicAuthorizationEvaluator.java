package cn.zhinan.uniapp.authorization.adapter.jdbc;

import cn.zhinan.uniapp.authorization.port.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcPublicAuthorizationEvaluator implements PublicAuthorizationEvaluator {
  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate reads;
  private final String mode;

  public JdbcPublicAuthorizationEvaluator(
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
                Map.of("ids", contexts.stream().map(PublicUseContext::workId).toList());
            var grants =
                jdbc.queryForList(
                    "SELECT * FROM public_authorization WHERE work_id IN (:ids)", parameters);
            var scopes =
                jdbc.queryForList(
                    "SELECT work_id,media_id FROM authorization_media WHERE work_id IN (:ids)",
                    parameters);
            Map<String, Map<String, Object>> byWork = new HashMap<>();
            grants.forEach(row -> byWork.put((String) row.get("work_id"), row));
            Map<String, Set<String>> media = new HashMap<>();
            scopes.forEach(
                row ->
                    media
                        .computeIfAbsent((String) row.get("work_id"), id -> new HashSet<>())
                        .add((String) row.get("media_id")));
            Map<String, AuthorizationReadResult> results = new HashMap<>();
            for (var requested : contexts) {
              var row = byWork.get(requested.workId());
              String expected = "production".equals(mode) ? "VERIFIED_AUTHORITY" : "DEMO_GENERATED";
              if (row == null
                  || !expected.equals(row.get("provenance"))
                  || ((String) row.get("evidence_ref")).isBlank()) {
                results.put(requested.workId(), unknown());
                continue;
              }
              var covered =
                  new PublicUseContext(
                      requested.workId(),
                      (String) row.get("release_id"),
                      (String) row.get("candidate_version"),
                      (String) row.get("binding_version"),
                      media.getOrDefault(requested.workId(), Set.of()),
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
                      covered,
                      ((LocalDateTime) row.get("valid_from")).toInstant(ZoneOffset.UTC),
                      ((LocalDateTime) row.get("valid_until")).toInstant(ZoneOffset.UTC),
                      (String) row.get("authority_revision"),
                      (String) row.get("fact_digest")));
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
                      "DATABASE_AUTHORITY_UNAVAILABLE")));
      return Map.copyOf(results);
    }
  }

  private static AuthorizationReadResult unknown() {
    return new AuthorizationReadResult.Decision(
        AuthorizationReadResult.Outcome.UNKNOWN, null, null, null, null, null);
  }
}
