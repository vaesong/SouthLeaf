package cn.zhinan.adminsecurity;

import static cn.zhinan.adminsecurity.domain.AdminPermission.*;
import static org.assertj.core.api.Assertions.*;

import cn.zhinan.adminsecurity.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AdminSecurityDomainTest {
  @Test
  void permissionsAreImmutableAndDefensivelyCopied() {
    Set<AdminPermission> grants = new HashSet<>(Set.of(CONTENT_READ));
    var account = new AdminAccount("staff", true, 1, grants);
    var principal = new AdminPrincipal("staff", grants);
    grants.clear();
    assertThat(account.permissions()).containsExactly(CONTENT_READ);
    assertThat(principal.permissions()).containsExactly(CONTENT_READ);
    assertThatThrownBy(() -> principal.permissions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> account.permissions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void defaultDenyAndNoImplicitPrivilegeEscalation() {
    var policy = new AdminAccessPolicy();
    var operator = new AdminPrincipal("staff", Set.of(CONTENT_EDIT));
    assertThat(policy.allows(null, CONTENT_EDIT)).isFalse();
    assertThat(policy.allows(operator, null)).isFalse();
    assertThat(policy.allows(operator, CONTENT_PUBLISH)).isFalse();
    assertThat(policy.allows(operator, STAFF_ACCESS_MANAGE)).isFalse();
    assertThat(policy.allows(operator, CONTENT_EDIT)).isTrue();
    assertThat(policy.allows(new AdminPrincipal("owner", Set.of()), CONTENT_READ)).isFalse();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"a b", "../staff", "staff@example.com", "员工"})
  void identifiersAreNotUserInputOrContactDetails(String id) {
    assertThatThrownBy(() -> new AdminAccount(id, true, 1, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNegativeSecurityVersion() {
    assertThatThrownBy(() -> new AdminAccount("staff", true, -1, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sessionRejectsInvalidDigestOrLifetime() {
    Instant now = Instant.parse("2026-10-18T08:00:00Z");
    assertThatThrownBy(() -> new AdminSession("raw-secret", "staff", 1, now, now.plusSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class);
    for (long seconds : List.of(-1L, 0L, 28801L)) {
      assertThatThrownBy(
              () -> new AdminSession("a".repeat(64), "staff", 1, now, now.plusSeconds(seconds)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
