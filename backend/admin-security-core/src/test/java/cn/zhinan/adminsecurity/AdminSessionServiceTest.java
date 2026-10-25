package cn.zhinan.adminsecurity;

import static cn.zhinan.adminsecurity.domain.AdminPermission.*;
import static org.assertj.core.api.Assertions.*;

import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.domain.*;
import cn.zhinan.adminsecurity.port.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AdminSessionServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");
  private final TestSessionTokenCodec tokens = new TestSessionTokenCodec();
  private final MemoryStore store = new MemoryStore();
  private final AdminSessionService service = at(NOW);

  private AdminSessionService at(Instant time) {
    return new AdminSessionService(
        store, tokens, Clock.fixed(time, ZoneOffset.UTC), Duration.ofHours(2));
  }

  @Test
  void storesDigestOnlyAndReturnsRedactedReceipt() {
    var issued = service.openForVerifiedCredentials("operator-1", 7);
    assertThat(issued.token()).matches("[A-Za-z0-9_-]{43}");
    assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
    assertThat(issued.toString()).doesNotContain(issued.token()).contains("REDACTED");
    assertThat(store.rows).containsOnlyKeys(tokens.digest(issued.token()));
    assertThat(store.rows.values().iterator().next().toString()).doesNotContain(issued.token());
    assertThat(service.authenticate(issued.token()).permissions()).containsExactly(CONTENT_READ);
  }

  @Test
  void expiryBoundaryIsExclusiveAndDoesNotSlide() {
    var issued = service.openForVerifiedCredentials("operator-1", 7);
    assertThat(at(issued.expiresAt().minusNanos(1)).authenticate(issued.token())).isNotNull();
    assertThatThrownBy(() -> at(issued.expiresAt()).authenticate(issued.token()))
        .isInstanceOf(AdminAuthenticationException.class);
    assertThat(store.rows.values().iterator().next().expiresAt()).isEqualTo(issued.expiresAt());
  }

  @Test
  void clockBeforeIssueFailsClosed() {
    var issued = service.openForVerifiedCredentials("operator-1", 7);
    assertThatThrownBy(() -> at(NOW.minusSeconds(1)).authenticate(issued.token()))
        .isInstanceOf(AdminAuthenticationException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {"short", "with spaces", "+++++++++++++++++++++++++++++++++++++++++++", "令牌"})
  void malformedTokensNeverReachStore(String token) {
    assertThatThrownBy(() -> service.authenticate(token))
        .isInstanceOf(AdminAuthenticationException.class);
    service.revoke(token);
    assertThat(store.reads).isZero();
    assertThat(store.revokes).isZero();
  }

  @Test
  void unknownTokenCannotAuthenticate() {
    assertThatThrownBy(() -> service.authenticate(tokens.generate()))
        .isInstanceOf(AdminAuthenticationException.class);
  }

  @Test
  void targetedRevokeDoesNotAffectOtherSessionAndIsIdempotent() {
    var first = service.openForVerifiedCredentials("operator-1", 7);
    var second = service.openForVerifiedCredentials("operator-1", 7);
    service.revoke(first.token());
    service.revoke(first.token());
    assertThatThrownBy(() -> service.authenticate(first.token()))
        .isInstanceOf(AdminAuthenticationException.class);
    assertThat(service.authenticate(second.token()).accountId()).isEqualTo("operator-1");
  }

  @Test
  void disabledAccountRejectsExistingSession() {
    var issued = service.openForVerifiedCredentials("operator-1", 7);
    store.account = new AdminAccount("operator-1", false, 7, Set.of(CONTENT_READ));
    assertThatThrownBy(() -> service.authenticate(issued.token()))
        .isInstanceOf(AdminAuthenticationException.class);
  }

  @Test
  void securityVersionInvalidatesAllSessionsAfterReset() {
    var first = service.openForVerifiedCredentials("operator-1", 7);
    var second = service.openForVerifiedCredentials("operator-1", 7);
    store.account = new AdminAccount("operator-1", true, 8, Set.of(CONTENT_READ));
    for (String token : List.of(first.token(), second.token())) {
      assertThatThrownBy(() -> service.authenticate(token))
          .isInstanceOf(AdminAuthenticationException.class);
    }
  }

  @Test
  void permissionsAreReadFreshNotCopiedFromIssuedSession() {
    var issued = service.openForVerifiedCredentials("operator-1", 7);
    store.account = new AdminAccount("operator-1", true, 7, Set.of());
    assertThat(service.authenticate(issued.token()).permissions()).isEmpty();
  }

  @Test
  void deletedOrMismatchedAccountRejectsSession() {
    var issued = service.openForVerifiedCredentials("operator-1", 7);
    store.account = new AdminAccount("other", true, 7, Set.of(CONTENT_READ));
    assertThatThrownBy(() -> service.authenticate(issued.token()))
        .isInstanceOf(AdminAuthenticationException.class);
    store.account = null;
    assertThatThrownBy(() -> service.authenticate(issued.token()))
        .isInstanceOf(AdminAuthenticationException.class);
  }

  @Test
  void rejectsAccountVersionChangedBetweenCredentialCheckAndCreation() {
    store.account = new AdminAccount("operator-1", true, 8, Set.of());
    assertThatThrownBy(() -> service.openForVerifiedCredentials("operator-1", 7))
        .isInstanceOf(AdminAuthenticationException.class);
    assertThat(store.rows).isEmpty();
  }

  @Test
  void disabledOrUnknownAccountCannotOpenSession() {
    assertThatThrownBy(() -> service.openForVerifiedCredentials("unknown", 7))
        .isInstanceOf(AdminAuthenticationException.class);
    store.account = new AdminAccount("operator-1", false, 7, Set.of());
    assertThatThrownBy(() -> service.openForVerifiedCredentials("operator-1", 7))
        .isInstanceOf(AdminAuthenticationException.class);
    assertThat(store.rows).isEmpty();
  }

  @Test
  void storageFailureIsNotAuthenticationSuccessOrSuccessfulLogout() {
    var issued = service.openForVerifiedCredentials("operator-1", 7);
    store.fail = true;
    assertThatThrownBy(() -> service.authenticate(issued.token()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.revoke(issued.token()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.openForVerifiedCredentials("operator-1", 7))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void substitutedStoreRowFailsClosed() {
    var issued = service.openForVerifiedCredentials("operator-1", 7);
    String digest = tokens.digest(issued.token());
    store.rows.put(
        digest, new AdminSession("a".repeat(64), "operator-1", 7, NOW, NOW.plusSeconds(300)));
    assertThatThrownBy(() -> service.authenticate(issued.token()))
        .isInstanceOf(AdminAuthenticationException.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 59, 28801})
  void rejectsUnsafeSessionLifetime(long seconds) {
    assertThatThrownBy(
            () ->
                new AdminSessionService(
                    store, tokens, Clock.systemUTC(), Duration.ofSeconds(seconds)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lifetimeLimitsAreAccepted() {
    for (long seconds : List.of(60L, 28800L)) {
      assertThat(
              new AdminSessionService(
                  store, tokens, Clock.systemUTC(), Duration.ofSeconds(seconds)))
          .isNotNull();
    }
  }

  @Test
  void malformedGeneratedSecretCannotBeStoredOrReturned() {
    SessionTokenCodec broken =
        new SessionTokenCodec() {
          @Override
          public String generate() {
            return "bad";
          }

          @Override
          public String digest(String token) {
            throw new AssertionError("must not digest malformed secret");
          }
        };
    var invalid =
        new AdminSessionService(
            store, broken, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(2));
    assertThatThrownBy(() -> invalid.openForVerifiedCredentials("operator-1", 7))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invalid generated session secret");
    assertThat(store.rows).isEmpty();
  }

  @Test
  void digestCollisionDoesNotOverwriteExistingSession() {
    String fixed = tokens.generate();
    SessionTokenCodec repeated =
        new SessionTokenCodec() {
          @Override
          public String generate() {
            return fixed;
          }

          @Override
          public String digest(String token) {
            return tokens.digest(token);
          }
        };
    var repeatedService =
        new AdminSessionService(
            store, repeated, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(2));
    repeatedService.openForVerifiedCredentials("operator-1", 7);
    assertThatThrownBy(() -> repeatedService.openForVerifiedCredentials("operator-1", 7))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Duplicate digest");
    assertThat(store.rows).hasSize(1);
    assertThat(repeatedService.authenticate(fixed)).isNotNull();
  }

  /** Test-only deterministic port double, not a production adapter or concurrency proof. */
  private static final class MemoryStore implements AdminSessionStore {
    private AdminAccount account = new AdminAccount("operator-1", true, 7, Set.of(CONTENT_READ));
    private final Map<String, AdminSession> rows = new HashMap<>();
    private int reads;
    private int revokes;
    private boolean fail;

    private void healthy() {
      if (fail) throw new IllegalStateException("Storage unavailable");
    }

    @Override
    public boolean createIfAccountCurrent(AdminSession session) {
      healthy();
      if (account == null || !session.activeFor(account, session.issuedAt())) return false;
      if (rows.putIfAbsent(session.tokenDigest(), session) != null)
        throw new IllegalStateException("Duplicate digest");
      return true;
    }

    @Override
    public Optional<SessionState> findCurrent(String digest) {
      healthy();
      reads++;
      return Optional.ofNullable(rows.get(digest))
          .map(session -> new SessionState(session, account));
    }

    @Override
    public void revoke(String digest, Instant now) {
      healthy();
      revokes++;
      rows.remove(digest);
    }
  }
}
