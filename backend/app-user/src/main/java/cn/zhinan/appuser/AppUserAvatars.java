package cn.zhinan.appuser;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** One current portrait. The long edge is scaled to 512 and stored as JPEG. */
public final class AppUserAvatars {
  private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");

  private AppUserAvatars() {}

  public static byte[] normalize(String contentType, byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > AppUserRules.AVATAR_LIMIT) {
      throw AppUserException.avatarRejected();
    }
    String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).strip();
    int separator = type.indexOf(';');
    if (separator >= 0) type = type.substring(0, separator).strip();
    if (!TYPES.contains(type)) throw AppUserException.avatarRejected();
    BufferedImage source = read(bytes);
    if (source == null) throw AppUserException.avatarRejected();
    int width = source.getWidth();
    int height = source.getHeight();
    if (width < 1 || height < 1 || width > 8000 || height > 8000) {
      throw AppUserException.avatarRejected();
    }
    int edge = Math.max(width, height);
    int targetWidth = width;
    int targetHeight = height;
    if (edge > AppUserRules.EDGE) {
      targetWidth = Math.max(1, width * AppUserRules.EDGE / edge);
      targetHeight = Math.max(1, height * AppUserRules.EDGE / edge);
    }
    BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = scaled.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
    } finally {
      graphics.dispose();
    }
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      if (!ImageIO.write(scaled, "jpeg", output)) throw AppUserException.avatarRejected();
      byte[] jpeg = output.toByteArray();
      if (jpeg.length == 0 || jpeg.length > AppUserRules.AVATAR_LIMIT) {
        throw AppUserException.avatarRejected();
      }
      return jpeg;
    } catch (AppUserException rejected) {
      throw rejected;
    } catch (Exception failure) {
      throw AppUserException.avatarRejected();
    }
  }

  private static BufferedImage read(byte[] bytes) {
    try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      if (input == null) return null;
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) return null;
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width > 8000 || height > 8000) throw AppUserException.avatarRejected();
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    } catch (AppUserException rejected) {
      throw rejected;
    } catch (Exception failure) {
      return null;
    }
  }
}
