package cn.zhinan.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CosObjectGatewayTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void createOnlyPutAcceptsAnIdenticalObjectAndRefusesADifferentOne() {
    byte[] bytes = "same-image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String key = ObjectKeys.sha256(bytes) + ".jpg";
    var transport = new ScriptedTransport();
    transport.reply(200, new byte[0]);
    gateway(transport).putCreateOnly(key, bytes);
    assertThat(transport.calls).containsExactly("PUT");

    transport.reply(409, new byte[0]);
    transport.reply(200, bytes);
    gateway(transport).putCreateOnly(key, bytes);
    assertThat(transport.calls).containsExactly("PUT", "PUT", "GET");

    transport.reply(409, new byte[0]);
    transport.reply(200, "other".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThatThrownBy(() -> gateway(transport).putCreateOnly(key, bytes))
        .isInstanceOf(ObjectStoreException.class)
        .extracting(ex -> ((ObjectStoreException) ex).code())
        .isEqualTo("OBJECT_CONFLICT");
  }

  @Test
  void putDoesNotReplaceAMissingConflictRead() {
    byte[] bytes = "image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String key = ObjectKeys.sha256(bytes) + ".png";
    var transport = new ScriptedTransport();
    transport.reply(409, new byte[0]);
    transport.reply(404, new byte[0]);
    assertThatThrownBy(() -> gateway(transport).putCreateOnly(key, bytes))
        .extracting(ex -> ((ObjectStoreException) ex).code())
        .isEqualTo("OBJECT_MISSING");
  }

  private static CosObjectGateway gateway(ObjectTransport transport) {
    return new CosObjectGateway(
        transport,
        new CosSigner("AKIDtestexample00000000000000000000", "test-secret-key"),
        "examplebucket-1250000000.cos.ap-guangzhou.myqcloud.com",
        CLOCK);
  }

  private static final class ScriptedTransport implements ObjectTransport {
    private final List<Response> responses = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    void reply(int status, byte[] body) {
      responses.add(new Response(status, body));
    }

    @Override
    public Response exchange(String method, String url, Map<String, String> headers, byte[] body) {
      calls.add(method);
      assertThat(url).startsWith("https://examplebucket-1250000000.cos.ap-guangzhou.myqcloud.com/");
      assertThat(headers.get("Authorization")).startsWith("q-sign-algorithm=sha1");
      assertThat(headers.values()).noneMatch(value -> value.contains("test-secret-key"));
      return responses.remove(0);
    }
  }
}
