package cn.zhinan.admincontent.adapter.cos;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.objectstore.CosObjectGateway;
import cn.zhinan.objectstore.CosSigner;
import cn.zhinan.objectstore.ObjectKeys;
import cn.zhinan.objectstore.ObjectTransport;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CosMediaObjectWriterTest {
  @Test
  void writerUsesTheGatewayAndKeepsTheConflictCode() {
    byte[] bytes = "derivative".getBytes(StandardCharsets.UTF_8);
    String key = ObjectKeys.sha256(bytes) + ".jpg";
    var transport = new ScriptedTransport();
    transport.statuses.add(200);
    var writer =
        new CosMediaObjectWriter(
            new CosObjectGateway(
                transport,
                new CosSigner("AKIDtestexample00000000000000000000", "test-secret-key"),
                "examplebucket-1250000000.cos.ap-guangzhou.myqcloud.com",
                Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC)));

    writer.write(key, bytes);
    transport.statuses.add(409);
    transport.bodies.add(new byte[0]);
    transport.statuses.add(200);
    transport.bodies.add("different".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> writer.write(key, bytes))
        .isInstanceOf(ContentException.class)
        .extracting(error -> ((ContentException) error).reason())
        .isEqualTo("OBJECT_CONFLICT");
  }

  private static final class ScriptedTransport implements ObjectTransport {
    private final List<Integer> statuses = new ArrayList<>();
    private final List<byte[]> bodies = new ArrayList<>();

    @Override
    public Response exchange(String method, String url, Map<String, String> headers, byte[] body) {
      int status = statuses.remove(0);
      byte[] payload = bodies.isEmpty() ? new byte[0] : bodies.remove(0);
      return new Response(status, payload);
    }
  }
}
