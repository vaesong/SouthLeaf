package cn.zhinan.uniapp.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.uniapp.config.MediaPlacement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaPlacementTest {
  @TempDir Path root;

  @Test
  void fileAndCdnAreCompleteAndMutuallyExclusive() throws Exception {
    Files.createDirectories(root);
    var file =
        MediaPlacement.open(
            Map.of(MediaPlacement.DELIVERY, "file", MediaPlacement.ROOT, root.toString()));
    assertThat(file.servesBytes()).isTrue();
    assertThat(file.delivery(Clock.systemUTC()).thumbnailUrl("m1", "ignored"))
        .contains("/thumbnail/content");

    Map<String, String> mixed = new HashMap<>();
    mixed.put(MediaPlacement.DELIVERY, "file");
    mixed.put(MediaPlacement.ROOT, root.toString());
    mixed.put(MediaPlacement.ORIGIN, "https://media.example.com");
    assertThatThrownBy(() -> MediaPlacement.open(mixed)).hasMessage("MEDIA_FILE_CONFIGURATION");

    Map<String, String> cdn = new HashMap<>();
    cdn.put(MediaPlacement.DELIVERY, "cdn");
    cdn.put(MediaPlacement.ORIGIN, "https://media.example.com");
    cdn.put(MediaPlacement.SECRET_ID, "AKIDtestexample00000000000000000000");
    cdn.put(MediaPlacement.SECRET_KEY, "test-secret-key");
    cdn.put(MediaPlacement.TTL, "600");
    var placed = MediaPlacement.open(cdn);
    assertThat(placed.servesBytes()).isFalse();
    String url =
        placed
            .delivery(Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC))
            .previewUrl("m1", "ab".repeat(32) + ".jpg");
    assertThat(url)
        .startsWith("https://media.example.com/" + "ab".repeat(32) + ".jpg?")
        .contains("q-sign-algorithm=sha1");
    assertThat(url).doesNotContain("test-secret-key");
    assertThat(placed.toString()).doesNotContain("test-secret-key");

    cdn.put(MediaPlacement.ROOT, root.toString());
    assertThatThrownBy(() -> MediaPlacement.open(cdn)).hasMessage("MEDIA_CDN_CONFIGURATION");
    assertThatThrownBy(() -> MediaPlacement.open(Map.of())).hasMessage("MEDIA_DELIVERY_REQUIRED");
  }
}
