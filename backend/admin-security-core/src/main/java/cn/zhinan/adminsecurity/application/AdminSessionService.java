package cn.zhinan.adminsecurity.application;

import cn.zhinan.adminsecurity.domain.AdminAuthenticationException;
import cn.zhinan.adminsecurity.domain.AdminPrincipal;
import cn.zhinan.adminsecurity.domain.AdminSession;
import cn.zhinan.adminsecurity.port.AdminSessionStore;
import cn.zhinan.adminsecurity.port.SessionTokenCodec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Plain use-case core; not a bean or public login endpoint. */
public final class AdminSessionService {
  private final AdminSessionStore store;
  private final SessionTokenCodec tokens;
  private final Clock clock;
  private final Duration lifetime;

  public AdminSessionService(
      AdminSessionStore store, SessionTokenCodec tokens, Clock clock, Duration lifetime) {
    this.store = Objects.requireNonNull(store);
    this.tokens = Objects.requireNonNull(tokens);
    this.clock = Objects.requireNonNull(clock);
    this.lifetime = Objects.requireNonNull(lifetime);
    if (lifetime.compareTo(Duration.ofMinutes(1)) < 0
        || lifetime.compareTo(AdminSession.MAX_LIFETIME) > 0) {
      throw new IllegalArgumentException(
          "Session lifetime must be between one minute and eight hours");
    }
  }

  /**
   * INTERNAL ONLY. Caller must verify credentials and capture their account security version first.
   * Never expose this account/version pair as an HTTP issuance request. The store rechecks it
   * atomically to reject a concurrent reset or disablement. No password verifier is provided here.
   */
  public IssuedSession openForVerifiedCredentials(String accountId, long verifiedSecurityVersion) {
    String token = tokens.generate();
    if (!wellFormed(token)) throw new IllegalStateException("Invalid generated session secret");
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    AdminSession session =
        new AdminSession(
            tokens.digest(token),
            accountId,
            verifiedSecurityVersion,
            now,
            now.plus(lifetime).truncatedTo(ChronoUnit.MICROS));
    if (!store.createIfAccountCurrent(session)) throw new AdminAuthenticationException();
    return new IssuedSession(token, session.expiresAt());
  }

  public AdminPrincipal authenticate(String token) {
    return authenticateWithExpiry(token).principal();
  }

  /** Authenticate against current store state and include the authoritative expiry for the UI. */
  public AuthenticatedSession authenticateWithExpiry(String token) {
    if (!wellFormed(token)) throw new AdminAuthenticationException();
    String digest = tokens.digest(token);
    var state = store.findCurrent(digest).orElseThrow(AdminAuthenticationException::new);
    if (state.session() == null
        || !digest.equals(state.session().tokenDigest())
        || !state.session().activeFor(state.account(), clock.instant())) {
      throw new AdminAuthenticationException();
    }
    return new AuthenticatedSession(
        new AdminPrincipal(state.account().accountId(), state.account().permissions()),
        state.session().expiresAt());
  }

  /**
   * No account enumeration; repeated logout is safe. Storage failures are not reported as success.
   */
  public void revoke(String token) {
    if (wellFormed(token)) store.revoke(tokens.digest(token), clock.instant());
  }

  private static boolean wellFormed(String token) {
    return token != null && token.matches("[A-Za-z0-9_-]{43}");
  }

  public record IssuedSession(String token, Instant expiresAt) {
    @Override
    public String toString() {
      return "IssuedSession[token=REDACTED, expiresAt=" + expiresAt + "]";
    }
  }

  public record AuthenticatedSession(AdminPrincipal principal, Instant expiresAt) {
    public AuthenticatedSession {
      Objects.requireNonNull(principal);
      Objects.requireNonNull(expiresAt);
    }
  }
}
