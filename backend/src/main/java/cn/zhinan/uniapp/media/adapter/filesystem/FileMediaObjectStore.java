package cn.zhinan.uniapp.media.adapter.filesystem;

import cn.zhinan.uniapp.media.port.MediaObjectStore;
import cn.zhinan.uniapp.publicquery.PublicQueryException;
import cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot.Variant;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only preprocessed content-addressed image derivatives are stored here, never arbitrary URLs. */
@Component
public class FileMediaObjectStore implements MediaObjectStore {
  private final Path root;

  public FileMediaObjectStore(@Value("${southleaf.media.root}") String root)
      throws java.io.IOException {
    this.root = Path.of(root).toRealPath();
    if (!Files.isDirectory(this.root))
      throw new IllegalArgumentException("Private media root must exist");
  }

  @Override
  public byte[] readVerified(Variant variant) {
    try {
      if (!variant.objectKey().matches("[a-f0-9]{64}\\.(png|jpg)")
          || !variant.objectKey().startsWith(variant.sha256() + "."))
        throw new IllegalArgumentException();
      Path file = root.resolve(variant.objectKey()).toRealPath();
      if (!file.startsWith(root)
          || !Files.isRegularFile(file)
          || Files.size(file) != variant.byteLength()
          || variant.byteLength() > 4194304) throw new IllegalArgumentException();
      byte[] bytes;
      try (var in = Files.newInputStream(file)) {
        bytes = in.readNBytes(4194305);
      }
      if (bytes.length != variant.byteLength()
          || !variant
              .sha256()
              .equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
        throw new IllegalArgumentException();
      return bytes;
    } catch (Exception ex) {
      throw new PublicQueryException(
          PublicQueryException.ErrorCode.PUBLIC_MEDIA_UNAVAILABLE,
          "Prepared media object unavailable");
    }
  }
}
