package cn.zhinan.adminsecurity.port;

import cn.zhinan.adminsecurity.domain.AdminAccount;
import cn.zhinan.adminsecurity.domain.AdminSession;
import java.time.Instant;
import java.util.Optional;

/**
 * Management persistence boundary, not an in-memory production fallback. Every failure must
 * propagate. Issue/revoke and their audit entries must commit together. Implementations must never
 * store or accept a raw bearer secret.
 */
public interface AdminSessionStore {
  /**
   * Atomically require an enabled account with the supplied security version and insert the session
   * plus audit. False means account changed/missing/disabled. Digest collisions must fail, not
   * upsert.
   */
  boolean createIfAccountCurrent(AdminSession session);

  /**
   * Read unrevoked session AND current account/permissions in a single consistent database view. No
   * stale cache, read replica lag or fallback. Deleted accounts/sessions return empty.
   */
  Optional<SessionState> findCurrent(String tokenDigest);

  /** Idempotent targeted revocation plus audit; unknown/already revoked digests are no-ops. */
  void revoke(String tokenDigest, Instant revokedAt);

  record SessionState(AdminSession session, AdminAccount account) {}
}
