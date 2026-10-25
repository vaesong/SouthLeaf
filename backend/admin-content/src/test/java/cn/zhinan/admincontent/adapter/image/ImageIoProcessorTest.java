package cn.zhinan.admincontent.adapter.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;
import org.junit.jupiter.api.Test;

class ImageIoProcessorTest {
  private final ImageIoProcessor processor = new ImageIoProcessor();

  @Test
  void pngProducesBoundedMetadataFreeDerivatives() throws Exception {
    byte[] source = pngWithTextChunk(2000, 1000, "GPS-SECRET-MARKER");
    assertThat(new String(source, StandardCharsets.ISO_8859_1)).contains("GPS-SECRET-MARKER");
    var result = processor.process(source, "image/png");
    assertThat(result.sourceFormat()).isEqualTo("PNG");
    assertThat(result.sourceWidth()).isEqualTo(2000);
    assertThat(result.variants()).hasSize(2);
    var thumb = result.variants().get(0);
    var preview = result.variants().get(1);
    assertThat(thumb.metadata().variant()).isEqualTo("thumbnail");
    assertThat(thumb.metadata().width()).isEqualTo(480);
    assertThat(thumb.metadata().height()).isEqualTo(240);
    assertThat(preview.metadata().width()).isEqualTo(1600);
    for (var variant : result.variants()) {
      assertThat(variant.metadata().objectKey())
          .isEqualTo(variant.metadata().sha256() + ".png")
          .matches("[0-9a-f]{64}\\.png");
      assertThat(new String(variant.bytes(), StandardCharsets.ISO_8859_1))
          .doesNotContain("GPS-SECRET-MARKER")
          .doesNotContain("tEXt");
      assertThat(ImageIO.read(new ByteArrayInputStream(variant.bytes()))).isNotNull();
    }
  }

  @Test
  void smallJpegIsNeverUpscaled() throws Exception {
    var result = processor.process(jpeg(300, 200), "image/jpeg");
    assertThat(result.variants())
        .allSatisfy(
            v -> {
              assertThat(v.metadata().width()).isEqualTo(300);
              assertThat(v.metadata().contentType()).isEqualTo("image/jpeg");
              assertThat(v.metadata().objectKey()).endsWith(".jpg");
            });
  }

  @Test
  void rejectsDisguisedTruncatedAppendedAndUnsupportedInputs() throws Exception {
    byte[] png = pngWithTextChunk(64, 64, "x");
    byte[] jpeg = jpeg(64, 64);
    expect(() -> processor.process(png, "image/jpeg"), ContentFailure.MEDIA_REJECTED);
    expect(() -> processor.process(jpeg, "image/png"), ContentFailure.MEDIA_REJECTED);
    expect(
        () -> processor.process(Arrays.copyOf(png, png.length - 20), "image/png"),
        ContentFailure.MEDIA_REJECTED);
    byte[] appended = Arrays.copyOf(png, png.length + 4);
    expect(() -> processor.process(appended, "image/png"), ContentFailure.MEDIA_REJECTED);
    byte[] truncatedJpeg = Arrays.copyOf(jpeg, jpeg.length / 2 + 2);
    truncatedJpeg[truncatedJpeg.length - 2] = (byte) 0xff;
    truncatedJpeg[truncatedJpeg.length - 1] = (byte) 0xd9;
    expect(() -> processor.process(truncatedJpeg, "image/jpeg"), ContentFailure.MEDIA_REJECTED);
    expect(() -> processor.process(png, "image/gif"), ContentFailure.MEDIA_UNSUPPORTED_TYPE);
    expect(() -> processor.process(new byte[0], "image/png"), ContentFailure.MEDIA_REJECTED);
    expect(
        () -> processor.process(new byte[4 * 1024 * 1024 + 1], "image/png"),
        ContentFailure.MEDIA_TOO_LARGE);
  }

  @Test
  void rejectsOversizedDimensionsBeforeDecoding() throws Exception {
    byte[] wide = pngWithTextChunk(8193, 1, "x");
    expect(() -> processor.process(wide, "image/png"), ContentFailure.MEDIA_REJECTED);
  }

  private static void expect(Runnable call, ContentFailure failure) {
    assertThatThrownBy(call::run)
        .isInstanceOf(ContentException.class)
        .extracting(e -> ((ContentException) e).failure())
        .isEqualTo(failure);
  }

  static byte[] pngWithTextChunk(int width, int height, String text) throws Exception {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    g.setColor(new Color(40, 120, 90));
    g.fillRect(0, 0, width, height);
    g.setColor(Color.ORANGE);
    g.fillOval(width / 4, height / 4, Math.max(1, width / 2), Math.max(1, height / 2));
    g.dispose();
    var writer = ImageIO.getImageWritersByFormatName("png").next();
    var parameters = writer.getDefaultWriteParam();
    var metadata =
        writer.getDefaultImageMetadata(
            ImageTypeSpecifier.createFromRenderedImage(image), parameters);
    var root = new IIOMetadataNode("javax_imageio_png_1.0");
    var textNode = new IIOMetadataNode("tEXt");
    var entry = new IIOMetadataNode("tEXtEntry");
    entry.setAttribute("keyword", "Comment");
    entry.setAttribute("value", text);
    textNode.appendChild(entry);
    root.appendChild(textNode);
    metadata.mergeTree("javax_imageio_png_1.0", root);
    var output = new ByteArrayOutputStream();
    try (var stream = ImageIO.createImageOutputStream(output)) {
      writer.setOutput(stream);
      writer.write(null, new IIOImage(image, null, metadata), parameters);
    } finally {
      writer.dispose();
    }
    return output.toByteArray();
  }

  static byte[] jpeg(int width, int height) throws Exception {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    g.setColor(new Color(200, 60, 60));
    g.fillRect(0, 0, width, height);
    g.dispose();
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "jpeg", output);
    return output.toByteArray();
  }
}
