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
 * signature, trailer, real decoder format, bounds and decoder warnings decide. Output is a fresh
 * raster encoded without any source metadata (EXIF/GPS/ICC/text chunks are not carried over).
 */
public final class ImageIoProcessor implements ImageProcessor {
  private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
  private static final byte[] PNG_IEND = {
    0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xae, 0x42, 0x60, (byte) 0x82
  };

  static {
    ImageIO.setUseCache(false);
  }

  @Override
  public ProcessedImage process(byte[] bytes, String declaredContentType) {
    if (bytes == null || bytes.length == 0) reject("MEDIA_EMPTY");
    if (bytes.length > ContentRules.MAX_UPLOAD_BYTES)
      throw new ContentException(ContentFailure.MEDIA_TOO_LARGE, "MEDIA_TOO_LARGE");
    final String format;
    if ("image/png".equals(declaredContentType)) format = "PNG";
    else if ("image/jpeg".equals(declaredContentType)) format = "JPEG";
    else throw new ContentException(ContentFailure.MEDIA_UNSUPPORTED_TYPE, "MEDIA_TYPE");
    if (format.equals("PNG") && !(startsWith(bytes, PNG_SIGNATURE) && endsWith(bytes, PNG_IEND)))
      reject("MEDIA_SIGNATURE");
    if (format.equals("JPEG")
        && !(bytes.length > 4
            && (bytes[0] & 0xff) == 0xff
            && (bytes[1] & 0xff) == 0xd8
            && (bytes[2] & 0xff) == 0xff
            && (bytes[bytes.length - 2] & 0xff) == 0xff
            && (bytes[bytes.length - 1] & 0xff) == 0xd9)) reject("MEDIA_SIGNATURE");
    BufferedImage decoded = decode(bytes, format);
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
    if (bytes.length > ContentRules.MAX_UPLOAD_BYTES) reject("MEDIA_DERIVATIVE_TOO_LARGE");
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

  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    return bytes.length >= prefix.length
        && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
  }

  private static boolean endsWith(byte[] bytes, byte[] suffix) {
    return bytes.length >= suffix.length
        && Arrays.equals(
            bytes, bytes.length - suffix.length, bytes.length, suffix, 0, suffix.length);
  }

  private static void reject(String reason) {
    throw new ContentException(ContentFailure.MEDIA_REJECTED, reason);
  }
}
