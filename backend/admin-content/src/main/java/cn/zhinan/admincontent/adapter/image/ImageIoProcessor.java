package cn.zhinan.admincontent.adapter.image;

import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import cn.zhinan.admincontent.domain.ContentModels.EncodedVariant;
import cn.zhinan.admincontent.domain.ContentModels.ProcessedImage;
import cn.zhinan.admincontent.domain.ContentModels.Variant;
import cn.zhinan.admincontent.domain.ContentRules;
import cn.zhinan.admincontent.port.ContentPorts.ImageProcessor;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * JDK ImageIO decoder/encoder for the S2 synthetic profile. MIME is only a hint: the byte
 * signature, a complete image, the real decoder format, bounds and decoder warnings decide. Bytes
 * after a finished JPEG or PNG are ignored, because exporters such as WeChat append a trailer.
 * Output is a fresh raster encoded without any source metadata (EXIF/GPS/ICC/text chunks are not
 * carried over).
 */
public final class ImageIoProcessor implements ImageProcessor {
  private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

  static {
    ImageIO.setUseCache(false);
  }

  @Override
  public ProcessedImage process(byte[] bytes, String declaredContentType) {
    if (bytes == null || bytes.length == 0) reject("MEDIA_EMPTY");
    if (bytes.length > ContentRules.MAX_UPLOAD_BYTES)
      throw new ContentException(ContentFailure.MEDIA_TOO_LARGE, "MEDIA_TOO_LARGE");
    final String format = imageFormat(declaredContentType);
    byte[] image = format.equals("PNG") ? pngImage(bytes) : jpegImage(bytes);
    BufferedImage decoded = decode(image, format);
    BufferedImage normalized = normalize(decoded, format);
    var variants = new ArrayList<EncodedVariant>(2);
    variants.add(encode("thumbnail", normalized, ContentRules.THUMBNAIL_EDGE, format));
    variants.add(encode("preview", normalized, ContentRules.PREVIEW_EDGE, format));
    return new ProcessedImage(
        format,
        ContentRules.sha256(bytes),
        bytes.length,
        decoded.getWidth(),
        decoded.getHeight(),
        variants);
  }

  private static BufferedImage decode(byte[] bytes, String format) {
    try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      if (input == null) reject("MEDIA_DECODE");
      var readers = ImageIO.getImageReaders(input);
      ImageReader reader = null;
      while (readers.hasNext()) {
        ImageReader candidate = readers.next();
        String name = candidate.getFormatName().toUpperCase(java.util.Locale.ROOT);
        if (name.equals(format) || (format.equals("JPEG") && name.equals("JPG"))) {
          reader = candidate;
          break;
        }
      }
      if (reader == null) reject("MEDIA_FORMAT_MISMATCH");
      try {
        var warned = new AtomicBoolean();
        reader.addIIOReadWarningListener(
            (IIOReadWarningListener) (source, warning) -> warned.set(true));
        reader.setInput(input, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width < 1
            || height < 1
            || width > ContentRules.MAX_EDGE
            || height > ContentRules.MAX_EDGE
            || (long) width * height > ContentRules.MAX_PIXELS) reject("MEDIA_DIMENSIONS");
        BufferedImage image = reader.read(0);
        if (warned.get() || image == null) reject("MEDIA_INTEGRITY");
        if (image.getWidth() != width || image.getHeight() != height) reject("MEDIA_INTEGRITY");
        return image;
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException failure) {
      if (failure instanceof ContentException known) throw known;
      reject("MEDIA_DECODE");
      throw new IllegalStateException("unreachable");
    }
  }

  private static BufferedImage normalize(BufferedImage source, String format) {
    int type = format.equals("PNG") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    var target = new BufferedImage(source.getWidth(), source.getHeight(), type);
    Graphics2D graphics = target.createGraphics();
    try {
      if (type == BufferedImage.TYPE_INT_RGB) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
      }
      graphics.drawImage(source, 0, 0, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }

  private static EncodedVariant encode(
      String variant, BufferedImage source, int maxEdge, String format) {
    double scale =
        Math.min(1.0, (double) maxEdge / Math.max(source.getWidth(), source.getHeight()));
    int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
    int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
    BufferedImage scaled = source;
    if (width != source.getWidth() || height != source.getHeight()) {
      scaled = new BufferedImage(width, height, source.getType());
      Graphics2D graphics = scaled.createGraphics();
      try {
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(
            RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(source, 0, 0, width, height, null);
      } finally {
        graphics.dispose();
      }
    }
    byte[] bytes = format.equals("PNG") ? writePng(scaled) : writeJpeg(scaled);
    if (bytes.length > ContentRules.MAX_STORED_OBJECT_BYTES) reject("MEDIA_DERIVATIVE_TOO_LARGE");
    String sha = ContentRules.sha256(bytes);
    String extension = format.equals("PNG") ? ".png" : ".jpg";
    String contentType = format.equals("PNG") ? "image/png" : "image/jpeg";
    return new EncodedVariant(
        new Variant(variant, sha + extension, sha, bytes.length, width, height, contentType),
        bytes);
  }

  private static byte[] writePng(BufferedImage image) {
    try {
      var output = new ByteArrayOutputStream();
      if (!ImageIO.write(image, "png", output)) reject("MEDIA_ENCODE");
      return output.toByteArray();
    } catch (IOException failure) {
      reject("MEDIA_ENCODE");
      throw new IllegalStateException("unreachable");
    }
  }

  private static byte[] writeJpeg(BufferedImage image) {
    var writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) reject("MEDIA_ENCODE");
    var writer = writers.next();
    var output = new ByteArrayOutputStream();
    try (var stream = new MemoryCacheImageOutputStream(output)) {
      writer.setOutput(stream);
      ImageWriteParam parameters = writer.getDefaultWriteParam();
      parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      parameters.setCompressionQuality(0.85f);
      writer.write(null, new IIOImage(image, null, null), parameters);
    } catch (IOException failure) {
      reject("MEDIA_ENCODE");
    } finally {
      writer.dispose();
    }
    return output.toByteArray();
  }

  private static String imageFormat(String declaredContentType) {
    if (declaredContentType == null)
      throw new ContentException(ContentFailure.MEDIA_UNSUPPORTED_TYPE, "MEDIA_TYPE");
    String declared = declaredContentType.trim().toLowerCase(java.util.Locale.ROOT);
    int parameter = declared.indexOf(';');
    if (parameter >= 0) declared = declared.substring(0, parameter).trim();
    if ("image/png".equals(declared) || "image/x-png".equals(declared)) return "PNG";
    if ("image/jpeg".equals(declared)
        || "image/jpg".equals(declared)
        || "image/pjpeg".equals(declared)) return "JPEG";
    throw new ContentException(ContentFailure.MEDIA_UNSUPPORTED_TYPE, "MEDIA_TYPE");
  }

  /** Keeps one complete PNG, including its IEND chunk, and drops any exporter trailer. */
  private static byte[] pngImage(byte[] bytes) {
    if (!startsWith(bytes, PNG_SIGNATURE)) reject("MEDIA_SIGNATURE");
    int offset = PNG_SIGNATURE.length;
    while (offset + 12 <= bytes.length) {
      long length = Integer.toUnsignedLong(readInt(bytes, offset));
      long chunkEnd = offset + 12L + length;
      if (length > Integer.MAX_VALUE - 12 || chunkEnd > bytes.length) reject("MEDIA_SIGNATURE");
      boolean iend =
          bytes[offset + 4] == 'I'
              && bytes[offset + 5] == 'E'
              && bytes[offset + 6] == 'N'
              && bytes[offset + 7] == 'D';
      offset = (int) chunkEnd;
      if (iend) return Arrays.copyOf(bytes, offset);
    }
    reject("MEDIA_SIGNATURE");
    throw new IllegalStateException("unreachable");
  }

  /** Keeps one complete JPEG through its real end marker, and drops any exporter trailer. */
  private static byte[] jpegImage(byte[] bytes) {
    if (bytes.length < 4 || u(bytes, 0) != 0xff || u(bytes, 1) != 0xd8 || u(bytes, 2) != 0xff)
      reject("MEDIA_SIGNATURE");
    int offset = 2;
    while (offset < bytes.length) {
      if (u(bytes, offset) != 0xff) reject("MEDIA_SIGNATURE");
      while (offset < bytes.length && u(bytes, offset) == 0xff) offset++;
      if (offset >= bytes.length) reject("MEDIA_SIGNATURE");
      int marker = u(bytes, offset++);
      if (marker == 0xd9) return Arrays.copyOf(bytes, offset);
      if (marker == 0x00 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
      if (marker == 0xda) {
        offset = skipSegment(bytes, offset);
        offset = jpegEnd(bytes, offset);
        return Arrays.copyOf(bytes, offset);
      }
      offset = skipSegment(bytes, offset);
    }
    reject("MEDIA_SIGNATURE");
    throw new IllegalStateException("unreachable");
  }

  private static int jpegEnd(byte[] bytes, int offset) {
    while (offset < bytes.length) {
      if (u(bytes, offset) != 0xff) {
        offset++;
        continue;
      }
      int markerAt = offset + 1;
      while (markerAt < bytes.length && u(bytes, markerAt) == 0xff) markerAt++;
      if (markerAt >= bytes.length) reject("MEDIA_SIGNATURE");
      int marker = u(bytes, markerAt);
      if (marker == 0x00) {
        offset = markerAt + 1;
        continue;
      }
      if (marker >= 0xd0 && marker <= 0xd7) {
        offset = markerAt + 1;
        continue;
      }
      if (marker == 0xd9) return markerAt + 1;
      reject("MEDIA_SIGNATURE");
    }
    reject("MEDIA_SIGNATURE");
    throw new IllegalStateException("unreachable");
  }

  private static int skipSegment(byte[] bytes, int offset) {
    if (offset + 1 >= bytes.length) reject("MEDIA_SIGNATURE");
    int length = (u(bytes, offset) << 8) | u(bytes, offset + 1);
    if (length < 2 || offset + length > bytes.length) reject("MEDIA_SIGNATURE");
    return offset + length;
  }

  private static int readInt(byte[] bytes, int offset) {
    return (u(bytes, offset) << 24)
        | (u(bytes, offset + 1) << 16)
        | (u(bytes, offset + 2) << 8)
        | u(bytes, offset + 3);
  }

  private static int u(byte[] bytes, int offset) {
    return bytes[offset] & 0xff;
  }

  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    return bytes.length >= prefix.length
        && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
  }

  private static void reject(String reason) {
    throw new ContentException(ContentFailure.MEDIA_REJECTED, reason);
  }
}
