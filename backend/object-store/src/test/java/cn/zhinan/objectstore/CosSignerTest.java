package cn.zhinan.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CosSignerTest {
  private static final String SECRET_ID = "AKIDtestexample00000000000000000000";
  private static final String SECRET_KEY = "test-secret-key";
  private static final String KEY = "ab".repeat(32) + ".jpg";
  private static final long START = 1_557_989_151L;
  private static final long END = 1_557_996_351L;

  @Test
  void headerSignatureMatchesTheCosV5Vector() {
    String authorization =
        new CosSigner(SECRET_ID, SECRET_KEY)
            .authorization(
                "PUT",
                "/" + KEY,
                Map.of(
                    "host",
                    "bucket.cos.ap-guangzhou.myqcloud.com",
                    "content-type",
                    "image/jpeg",
                    "x-cos-forbid-overwrite",
                    "true"),
                START,
                END);

    assertThat(authorization)
        .isEqualTo(
            "q-sign-algorithm=sha1&q-ak="
                + SECRET_ID
                + "&q-sign-time=1557989151;1557996351&q-key-time=1557989151;1557996351"
                + "&q-header-list=content-type;host;x-cos-forbid-overwrite&q-url-param-list="
                + "&q-signature=718d53c41c54b2a92ac27e8c4b6a846a8aa43a0e");
    assertThat(authorization).doesNotContain(SECRET_KEY);
    assertThat(new CosSigner(SECRET_ID, SECRET_KEY).toString()).doesNotContain(SECRET_KEY);
  }

  @Test
  void querySignatureCoversEverySigningParameterExceptItself() {
    String query =
        new CosSigner(SECRET_ID, SECRET_KEY)
            .signedQuery("GET", "/" + KEY, "media.example.com", START, END);

    assertThat(query)
        .isEqualTo(
            "q-ak="
                + SECRET_ID
                + "&q-header-list=host&q-key-time=1557989151%3B1557996351"
                + "&q-sign-algorithm=sha1&q-sign-time=1557989151%3B1557996351"
                + "&q-url-param-list=q-ak%3Bq-header-list%3Bq-key-time%3Bq-sign-algorithm%3Bq-sign-time%3Bq-url-param-list"
                + "&q-signature=fc3cfe37ac5c976f8521a0796a254a2ac96ccf44");
  }

  @Test
  void credentialsAndWindowAreRefusedRatherThanGuessed() {
    assertThatThrownBy(() -> new CosSigner("short", SECRET_KEY))
        .isInstanceOf(ObjectStoreException.class);
    assertThatThrownBy(() -> new CosSigner(SECRET_ID, "bad key"))
        .isInstanceOf(ObjectStoreException.class);
    assertThatThrownBy(
            () -> new CosSigner(SECRET_ID, SECRET_KEY).authorization("GET", "key", Map.of(), 2, 1))
        .isInstanceOf(ObjectStoreException.class);
  }
}
