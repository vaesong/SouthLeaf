package cn.zhinan.adminruntime.crypto;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SecureSessionTokenCodecTest {
  @Test
  void generatedSecretsAreUrlSafeAndDecodeTo256Bits() {
    var codec = new SecureSessionTokenCodec();
    Set<String> sample = new HashSet<>();
    for (int i = 0; i < 256; i++) {
      String token = codec.generate();
      assertThat(token).matches("[A-Za-z0-9_-]{43}");
      assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
      sample.add(token);
    }
    assertThat(sample).hasSize(256); // Smoke test, not statistical or cryptographic proof.
  }

  @Test
  void digestUsesKnownSha256VectorAndIsDeterministic() {
    var codec = new SecureSessionTokenCodec();
    assertThat(codec.digest("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    String token = codec.generate();
    assertThat(codec.digest(token)).hasSize(64).isEqualTo(codec.digest(token)).isNotEqualTo(token);
    assertThat(codec.digest(token + "a")).isNotEqualTo(codec.digest(token));
  }
}
