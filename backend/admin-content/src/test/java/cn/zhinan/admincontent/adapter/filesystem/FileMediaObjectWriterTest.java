package cn.zhinan.admincontent.adapter.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentRules;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMediaObjectWriterTest {
  @TempDir Path root;

  @Test
  void writesContentAddressedCreateOnlyObjects() throws Exception {
    var writer = new FileMediaObjectWriter(root);
    byte[] bytes = {1, 2, 3};
    String key = ContentRules.sha256(bytes) + ".png";
    writer.write(key, bytes);
    writer.write(key, bytes);
    assertThat(writer.read(key)).containsExactly(1, 2, 3);
    try (var files = Files.list(root)) {
      assertThat(files.map(p -> p.getFileName().toString())).containsExactly(key);
    }
    assertThatThrownBy(() -> writer.write("0".repeat(64) + ".png", bytes))
        .isInstanceOf(ContentException.class);
    assertThatThrownBy(() -> writer.write("../" + key, bytes)).isInstanceOf(ContentException.class);
    Files.write(root.resolve(ContentRules.sha256(new byte[] {9}) + ".png"), new byte[] {8});
    assertThatThrownBy(
            () -> writer.write(ContentRules.sha256(new byte[] {9}) + ".png", new byte[] {9}))
        .isInstanceOf(ContentException.class);
  }

  @Test
  void rejectsRelativeRoot() {
    assertThatThrownBy(() -> new FileMediaObjectWriter(Path.of("relative")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
