package cn.zhinan.uniapp.media.adapter.filesystem;

import static org.assertj.core.api.Assertions.*;

import cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot.Variant;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMediaObjectStoreTest {
  @TempDir Path root;

  private String sha(byte[] b) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
  }

  @Test
  void exactContentAddressAndLengthAreVerified() throws Exception {
    byte[] bytes = {1, 2, 3};
    String hash = sha(bytes);
    Files.write(root.resolve(hash + ".png"), bytes);
    assertThat(
            new FileMediaObjectStore(root.toString())
                .readVerified(new Variant(hash + ".png", hash, 3, 1, 1, "image/png")))
        .containsExactly(bytes);
  }

  @Test
  void pathTraversalAndUnknownKeysAreRejected() throws Exception {
    assertThatThrownBy(
            () ->
                new FileMediaObjectStore(root.toString())
                    .readVerified(new Variant("../x.png", "x", 1, 1, 1, "image/png")))
        .hasMessageContaining("unavailable");
  }

  @Test
  void modifiedBytesCannotReusePublishedDigest() throws Exception {
    byte[] bytes = {1};
    String hash = sha(bytes);
    Files.write(root.resolve(hash + ".png"), new byte[] {2});
    assertThatThrownBy(
            () ->
                new FileMediaObjectStore(root.toString())
                    .readVerified(new Variant(hash + ".png", hash, 1, 1, 1, "image/png")))
        .hasMessageContaining("unavailable");
  }

  @Test
  void missingObjectIsUnavailableNotGenerated() throws Exception {
    String hash = "0".repeat(64);
    assertThatThrownBy(
            () ->
                new FileMediaObjectStore(root.toString())
                    .readVerified(new Variant(hash + ".png", hash, 1, 1, 1, "image/png")))
        .hasMessageContaining("unavailable");
  }
}
