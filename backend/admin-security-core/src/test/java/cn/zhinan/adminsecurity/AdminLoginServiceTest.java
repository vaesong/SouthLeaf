package cn.zhinan.adminsecurity;

import static org.assertj.core.api.Assertions.*;

import cn.zhinan.adminsecurity.application.AdminLoginService;
import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.domain.*;
import cn.zhinan.adminsecurity.port.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AdminLoginServiceTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
  private static final UUID REQUEST = UUID.fromString("a4873c17-12b7-49fd-a9f6-5be172b65f39");
  private final List<AuthenticationEvent> events = new ArrayList<>();
  private final FakeVerifier verifier = new FakeVerifier();
  private final FakeStore store = new FakeStore();
  private Optional<CredentialSnapshot> fact =
      Optional.of(new CredentialSnapshot("Admin.Name", true, 7, "synthetic-hash"));
  private RuntimeException lookupFailure;
  private RuntimeException eventFailure;
  private int lookups;
  private final AdminLoginService service =
      new AdminLoginService(
          id -> {
            lookups++;
            if (lookupFailure != null) throw lookupFailure;
            return fact;
          },
          verifier,
          new AdminSessionService(
              store,
              new SessionTokenCodec() {
                public String generate() {
                  return "a".repeat(43);
                }

                public String digest(String raw) {
                  return "b".repeat(64);
                }
              },
              CLOCK,
              Duration.ofHours(1)),
          event -> {
            if (eventFailure != null) throw eventFailure;
            events.add(event);
          },
          CLOCK);

  @Test
  void successUsesLookupVersionAndClearsOnlyOwnedCopy() {
    char[] raw = "  é 👩‍💻  ".toCharArray();
    char[] before = raw.clone();
    var result = service.login("Admin.Name", raw, REQUEST);
    assertThat(store.created.accountId()).isEqualTo("Admin.Name");
    assertThat(store.created.securityVersion()).isEqualTo(7);
    assertThat(raw).isEqualTo(before);
    assertThat(verifier.observed).isEqualTo(new String(before));
    assertThat(verifier.received).containsOnly('\0');
    assertThat(verifier.received).isNotSameAs(raw);
    assertThat(result.toString()).doesNotContain(result.token());
    assertThat(events).isEmpty();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" bad", "bad ", "ADMIN/NAME", "管理"})
  void invalidIdUsesDummyAndNeverLookup(String id) {
    assertDenied(id, "x".toCharArray());
    assertThat(lookups).isZero();
    assertThat(verifier.dummies).isEqualTo(1);
    assertThat(events.getFirst().knownAccountId()).isNull();
  }

  @Test
  void absentAccountAlwaysDeniesEvenWhenDummyPasswordMatches() {
    fact = Optional.empty();
    assertDenied("Admin.Name", "x".toCharArray());
    assertThat(verifier.dummies).isEqualTo(1);
    assertThat(verifier.verifications).isZero();
    assertThat(events.getFirst().knownAccountId()).isNull();
  }

  @Test
  void disabledAccountDoesNotVerifyItsHash() {
    fact = Optional.of(new CredentialSnapshot("Admin.Name", false, 7, "invalid-but-unused"));
    assertDenied("Admin.Name", "x".toCharArray());
    assertThat(verifier.dummies).isEqualTo(1);
    assertThat(verifier.verifications).isZero();
  }

  @Test
  void wrongPasswordUsesSamePublicFailure() {
    verifier.result = new PasswordMatch(false, false);
    assertDenied("Admin.Name", "x".toCharArray());
    assertThat(verifier.verifications).isEqualTo(1);
  }

  @Test
  void concurrentChangeRejectsUsingExistingStoreRecheck() {
    store.current = false;
    assertThatThrownBy(() -> service.login("Admin.Name", new char[] {'x'}, REQUEST))
        .isExactlyInstanceOf(AdminAuthenticationException.class);
    assertThat(store.attempts).isEqualTo(1);
    assertThat(store.created).isNull();
    assertThat(events)
        .extracting(AuthenticationEvent::kind)
        .containsExactly(AuthenticationEvent.Kind.LOGIN_DENIED);
    assertThat(verifier.received).containsOnly('\0');
  }

  @Test
  void upgradeSignalPrecedesIssuanceAndDoesNotRewriteCredentials() {
    verifier.result = new PasswordMatch(true, true);
    service.login("Admin.Name", new char[] {'x'}, REQUEST);
    assertThat(events)
        .extracting(AuthenticationEvent::kind)
        .containsExactly(AuthenticationEvent.Kind.CREDENTIAL_UPGRADE_REQUIRED);
    assertThat(events.getFirst().requestId()).isEqualTo(REQUEST);
    assertThat(events.getFirst().occurredAt()).isEqualTo(CLOCK.instant());
    assertThat(store.attempts).isEqualTo(1);
    assertThat(fact.orElseThrow().credentialHash()).isEqualTo("synthetic-hash");
  }

  @Test
  void failedUpgradeAuditPreventsIssuance() {
    verifier.result = new PasswordMatch(true, true);
    eventFailure = new IllegalStateException("synthetic dependency failure");
    assertThatThrownBy(() -> service.login("Admin.Name", new char[] {'x'}, REQUEST))
        .isSameAs(eventFailure);
    assertThat(store.attempts).isZero();
    assertThat(verifier.received).containsOnly('\0');
  }

  @Test
  void failedDenialAuditIsNotReportedAsBadCredentials() {
    fact = Optional.empty();
    eventFailure = new IllegalStateException("synthetic dependency failure");
    assertThatThrownBy(() -> service.login("Admin.Name", new char[] {'x'}, REQUEST))
        .isSameAs(eventFailure);
    assertThat(store.attempts).isZero();
  }

  @Test
  void lookupFailureIsNotAbsence() {
    lookupFailure = new IllegalStateException("synthetic dependency failure");
    assertThatThrownBy(() -> service.login("Admin.Name", new char[] {'x'}, REQUEST))
        .isSameAs(lookupFailure);
    assertThat(verifier.verifications + verifier.dummies).isZero();
    assertThat(store.attempts).isZero();
    assertThat(events).isEmpty();
  }

  @Test
  void mismatchedLookupIdentityFailsClosed() {
    fact = Optional.of(new CredentialSnapshot("admin.name", true, 7, "synthetic-hash"));
    assertThatThrownBy(() -> service.login("Admin.Name", new char[] {'x'}, REQUEST))
        .isInstanceOf(CredentialDependencyException.class);
    assertThat(verifier.verifications).isZero();
    assertThat(store.attempts).isZero();
  }

  @Test
  void verifierFailurePropagatesAndClearsOwnedArray() {
    verifier.failure =
        new CredentialDependencyException(CredentialDependencyException.Reason.INVALID_STORED_HASH);
    assertThatThrownBy(() -> service.login("Admin.Name", new char[] {'x'}, REQUEST))
        .isSameAs(verifier.failure);
    assertThat(verifier.received).containsOnly('\0');
    assertThat(store.attempts).isZero();
    assertThat(events).isEmpty();
  }

  @Test
  void dummyFailurePropagatesAndClearsOwnedArray() {
    fact = Optional.empty();
    verifier.failure =
        new CredentialDependencyException(CredentialDependencyException.Reason.CAPACITY_EXHAUSTED);
    assertThatThrownBy(() -> service.login("Admin.Name", new char[] {'x'}, REQUEST))
        .isSameAs(verifier.failure);
    assertThat(verifier.received).containsOnly('\0');
    assertThat(store.attempts).isZero();
  }

  @Test
  void storeFailureIsNotLoginDenial() {
    store.failure = new IllegalStateException("synthetic dependency failure");
    assertThatThrownBy(() -> service.login("Admin.Name", new char[] {'x'}, REQUEST))
        .isSameAs(store.failure);
    assertThat(events).isEmpty();
    assertThat(verifier.received).containsOnly('\0');
  }

  @Test
  void oversizedAndMalformedPasswordsRejectBeforeCopyLookupOrKdf() {
    for (char[] password :
        new char[][] {
          null,
          new char[0],
          new char[257],
          "x".repeat(129).toCharArray(),
          new char[] {'\uD800'},
          new char[] {'\uDC00'}
        }) {
      assertDenied("Admin.Name", password);
    }
    assertThat(lookups).isZero();
    assertThat(verifier.verifications + verifier.dummies).isZero();
  }

  @Test
  void boundsCountUnicodeScalarsAndAcceptOriginalValuesWithoutCreationMinimum() {
    assertThat(PasswordInput.isBounded("😀".repeat(128).toCharArray())).isTrue();
    assertThat(PasswordInput.isBounded("😀".repeat(129).toCharArray())).isFalse();
    assertThat(PasswordInput.isBounded("x".toCharArray())).isTrue();
    assertThat(PasswordInput.isBounded(" ".repeat(128).toCharArray())).isTrue();
    assertThat(PasswordInput.isBounded(new char[] {'\uD800', 'x'})).isFalse();
    assertThat(PasswordInput.isBounded(new char[] {'x', '\uDC00'})).isFalse();
  }

  @Test
  void valueObjectsValidateAndRedact() {
    assertThat(fact.orElseThrow().toString()).doesNotContain("synthetic-hash", "Admin.Name");
    assertThatThrownBy(() -> new PasswordMatch(false, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CredentialSnapshot("x", true, -1, "hash"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CredentialSnapshot("x", true, 1, "x".repeat(257)))
        .isInstanceOf(CredentialDependencyException.class);
    assertThatThrownBy(() -> new CredentialSnapshot("x", true, 1, null))
        .isInstanceOf(CredentialDependencyException.class);
    assertThatThrownBy(() -> new CredentialSnapshot("x", true, 1, ""))
        .isInstanceOf(CredentialDependencyException.class);
    assertThatThrownBy(
            () ->
                new AuthenticationEvent(
                    AuthenticationEvent.Kind.CREDENTIAL_UPGRADE_REQUIRED,
                    null,
                    REQUEST,
                    CLOCK.instant()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AuthenticationEvent(
                    AuthenticationEvent.Kind.LOGIN_DENIED, "bad/id", REQUEST, CLOCK.instant()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(AdminAccount.isValidAccountId("x")).isTrue();
    assertThat(AdminAccount.isValidAccountId("A._-09")).isTrue();
    assertThat(AdminAccount.isValidAccountId("x".repeat(64))).isTrue();
    assertThat(AdminAccount.isValidAccountId("x".repeat(65))).isFalse();
  }

  private void assertDenied(String id, char[] password) {
    assertThatThrownBy(() -> service.login(id, password, REQUEST))
        .isExactlyInstanceOf(AdminAuthenticationException.class)
        .hasMessage("Administrator authentication required");
    assertThat(store.attempts).isZero();
    assertThat(events.getLast().kind()).isEqualTo(AuthenticationEvent.Kind.LOGIN_DENIED);
  }

  private static final class FakeVerifier implements PasswordVerifier {
    PasswordMatch result = new PasswordMatch(true, false);
    RuntimeException failure;
    char[] received;
    String observed;
    int dummies;
    int verifications;

    private void observe(char[] password) {
      received = password;
      observed = new String(password);
      if (failure != null) throw failure;
    }

    public PasswordMatch verify(char[] password, String hash) {
      verifications++;
      observe(password);
      return result;
    }

    public void consumeDummyCost(char[] password) {
      dummies++;
      observe(password);
    }
  }

  private static final class FakeStore implements AdminSessionStore {
    AdminSession created;
    int attempts;
    boolean current = true;
    RuntimeException failure;

    public boolean createIfAccountCurrent(AdminSession session) {
      attempts++;
      if (failure != null) throw failure;
      if (!current) return false;
      created = session;
      return true;
    }

    public Optional<SessionState> findCurrent(String digest) {
      throw new AssertionError("Not a login operation");
    }

    public void revoke(String digest, Instant now) {
      throw new AssertionError("Not a login operation");
    }
  }
}
