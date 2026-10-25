package cn.zhinan.adminruntime;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminOriginPolicyTest {
  private static final AdminOriginPolicy DEPLOYED =
      AdminOriginPolicy.allowing(URI.create("https://admin.example.com"));

  @Test
  void comparesTheDefaultPortSpellingTheBrowserSends() {
    assertThat(DEPLOYED.accepts("https://admin.example.com")).isTrue();
    assertThat(DEPLOYED.accepts("https://admin.example.com:443")).isTrue();
    assertThat(DEPLOYED.accepts("https://ADMIN.example.com:443")).isTrue();
    assertThat(DEPLOYED.accepts("https://admin.example.com:8443")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://admin.example.com.evil.test",
        "https://evil-admin.example.com",
        "https://admin.example.com.evil.test:443",
        "http://admin.example.com",
        "https://admin.example.com/some/path",
        "https://user:secret@admin.example.com",
        "https://admin.example.com?x=1",
        "null",
        "",
        "admin.example.com"
      })
  void rejectsEverythingThatIsNotTheConfiguredOrigin(String header) {
    assertThat(DEPLOYED.accepts(header)).isFalse();
    assertThat(DEPLOYED.accepts(null)).isFalse();
  }

  @Test
  void keepsTheAdministrativePortExplicit() {
    var fixture = AdminOriginPolicy.allowing(URI.create("https://localhost:18443"));
    assertThat(fixture.accepts("https://localhost:18443")).isTrue();
    assertThat(fixture.accepts("https://localhost")).isFalse();
  }

  @Test
  void deniesEverythingWhenTheListenerIsSwitchedOff() {
    assertThat(AdminOriginPolicy.denyAll().accepts("https://admin.example.com")).isFalse();
    assertThat(AdminOriginPolicy.canonicalize("https://admin.example.com"))
        .isEqualTo("https://admin.example.com:443");
  }
}
