package cn.zhinan.admincontent.adapter.filesystem;

import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import cn.zhinan.admincontent.domain.ContentRules;
import cn.zhinan.admincontent.port.ContentPorts.MediaObjectWriter;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Create-only, content-addressed writer for the shared private media root. Keys are always
 * server-computed {@code <sha256>.(jpg|png)}; an existing object is accepted only if identical.
 * Orphans are possible on later database failure and are unreadable publicly (D-302 §13.2).
 */
public final class FileMediaObjectWriter implements MediaObjectWriter {
  private static final Pattern KEY = Pattern.compile("([0-9a-f]{64})\\.(jpg|png)");
  private final Path root;

  public FileMediaObjectWriter(Path configuredRoot) {
    try {
      if (configuredRoot == null
          || !configuredRoot.isAbsolute()
          || !Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS))
        throw new IllegalArgumentException("Media root rejected");
      this.root = configuredRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (IOException ex) {
      throw new IllegalArgumentException("Media root rejected");
    }
  }

  @Override
  public void write(String objectKey, byte[] bytes) {
    var match = KEY.matcher(objectKey == null ? "" : objectKey);
    if (!match.matches() || !match.group(1).equals(ContentRules.sha256(bytes)))
      throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "OBJECT_KEY_INVALID");
    Path target = root.resolve(objectKey);
    if (!target.getParent().equals(root))
      throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "OBJECT_KEY_INVALID");
    try {
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        verifyExisting(target, bytes);
        return;
      }
      Path temporary = root.resolve(".upload-" + UUID.randomUUID() + ".tmp");
      try {
        Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (FileAlreadyExistsException concurrent) {
        verifyExisting(target, bytes);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException ex) {
      throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "OBJECT_WRITE");
    }
  }

  @Override
  public byte[] read(String objectKey) {
    if (objectKey == null || !KEY.matcher(objectKey).matches())
      throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "OBJECT_KEY_INVALID");
    Path target = root.resolve(objectKey);
    try {
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
          || Files.size(target) > ContentRules.MAX_UPLOAD_BYTES)
        throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "OBJECT_MISSING");
      return Files.readAllBytes(target);
    } catch (IOException ex) {
      throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "OBJECT_READ");
    }
  }

  private static void verifyExisting(Path target, byte[] bytes) throws IOException {
    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
        || Files.size(target) != bytes.length
        || !ContentRules.sha256(Files.readAllBytes(target)).equals(ContentRules.sha256(bytes)))
      throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "OBJECT_CONFLICT");
  }
}
