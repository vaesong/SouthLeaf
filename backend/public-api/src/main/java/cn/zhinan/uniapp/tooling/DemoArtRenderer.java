package cn.zhinan.uniapp.tooling;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/** Original geometric demo art, not a photograph or customer authorization. */
final class DemoArtRenderer {
  private DemoArtRenderer() {}

  static byte[] render(String key, String variant) throws java.io.IOException {
    int w = "thumbnail".equals(variant) ? 360 : 960;
    boolean qr = key.equals("demo:qr-placeholder");
    boolean staff = key.startsWith("demo:staff-");
    int h = qr ? w : w * 5 / 4;
    BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      float hue = Math.floorMod(key.hashCode(), 360) / 360f;
      g.setColor(Color.getHSBColor(hue, .12f, .94f));
      g.fillRect(0, 0, w, h);
      g.setColor(Color.getHSBColor(hue, .28f, .63f));
      g.fillOval(w / 4, h / 5, w * 3 / 4, w * 3 / 4);
      g.setColor(new Color(245, 239, 221));
      g.fillRoundRect(w / 8, h / 3, w / 2, h / 2, w / 4, w / 4);
      g.setColor(Color.getHSBColor(hue, .35f, .36f));
      g.fillRect(0, h * 4 / 5, w, h / 5);
      g.setColor(new Color(250, 247, 236));
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, w / 25));
      g.drawString("SOUTHLEAF / DEMO", w / 12, h * 9 / 10);
      g.setColor(new Color(65, 75, 62));
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, w / 24));
      g.drawString(key.replace("demo:", "").toUpperCase(), w / 12, h / 9);
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, w / 40));
      g.drawString("ORIGINAL DEMO ART - NOT A CLIENT PHOTO", w / 12, h / 7);
      if (qr) {
        g.setColor(new Color(250, 248, 242));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(202, 195, 178));
        g.setStroke(new BasicStroke(w / 150f));
        g.drawRect(w / 12, w / 12, w * 5 / 6, w * 5 / 6);
        g.drawLine(w / 4, w / 4, w * 3 / 4, w * 3 / 4);
        g.drawLine(w * 3 / 4, w / 4, w / 4, w * 3 / 4);
        g.setColor(new Color(250, 248, 242));
        g.fillRect(w / 8, w * 2 / 5, w * 3 / 4, w / 5);
        g.setColor(new Color(120, 121, 100));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, w / 14));
        g.drawString("DEMO QR", w / 5, w * 12 / 25);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, w / 28));
        g.drawString("NOT SCANNABLE", w / 5, w * 14 / 25);
      } else if (staff) {
        g.setColor(new Color(234, 233, 222));
        g.fillRect(0, 0, w, h);
        g.setColor(key.contains("makeup") ? new Color(176, 148, 133) : new Color(124, 151, 135));
        g.fillOval(w * 5 / 16, h / 6, w * 3 / 8, w * 3 / 8);
        g.fillRoundRect(w / 6, h * 11 / 24, w * 2 / 3, h * 5 / 8, w / 3, w / 3);
        g.setColor(new Color(250, 248, 242));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, w / 28));
        g.drawString("ORIGINAL ILLUSTRATION / DEMO", w / 14, h * 23 / 24);
      }
    } finally {
      g.dispose();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }
}
