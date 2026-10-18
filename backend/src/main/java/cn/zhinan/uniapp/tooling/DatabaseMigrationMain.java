package cn.zhinan.uniapp.tooling;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Explicit local/deployment tool. Never registered as a web controller or Spring application bean.
 */
public final class DatabaseMigrationMain {
  private DatabaseMigrationMain() {}

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Required environment variable: " + name);
    return value;
  }

  public static void main(String[] args) throws Exception {
    var ds =
        new DriverManagerDataSource(
            required("SOUTHLEAF_MIGRATION_URL"),
            required("SOUTHLEAF_MIGRATION_USERNAME"),
            required("SOUTHLEAF_MIGRATION_PASSWORD"));
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    if (args.length == 0) {
      System.out.println("SCHEMA_MIGRATION_PASS");
      return;
    }
    if (args.length != 1 || !"--seed-demo".equals(args[0]))
      throw new IllegalArgumentException("Only explicit --seed-demo is supported");
    Path root = Path.of(required("SOUTHLEAF_MEDIA_ROOT"));
    Files.createDirectories(root);
    root = root.toRealPath();
    try (var connection = ds.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (var check = connection.prepareStatement("SELECT COUNT(*) FROM catalog_release");
            var result = check.executeQuery()) {
          result.next();
          if (result.getInt(1) != 0)
            throw new IllegalStateException(
                "Demo import requires an empty catalog; no merge/reset allowed");
        }
        ScriptUtils.executeSqlScript(
            connection,
            new EncodedResource(new ClassPathResource("db/demo/seed.sql"), StandardCharsets.UTF_8));
        String manifest =
            new String(
                new ClassPathResource("db/demo/assets.csv").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int count = 0;
        for (String line : manifest.lines().filter(s -> !s.isBlank()).toList()) {
          String[] parts = line.split("\\|", -1);
          if (parts.length != 2 || !parts[1].startsWith("demo:"))
            throw new IllegalStateException("Invalid demo media manifest");
          for (String variant : List.of("thumbnail", "preview")) {
            byte[] bytes = DemoArtRenderer.render(parts[1], variant);
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null
                || bytes.length > 4194304
                || image.getWidth() > 8192
                || image.getHeight() > 8192)
              throw new IllegalStateException("Invalid prepared image");
            String sha =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String key = sha + ".png";
            Path file = root.resolve(key);
            if (Files.exists(file)) {
              if (!Arrays.equals(bytes, Files.readAllBytes(file)))
                throw new IllegalStateException("Content-addressed object mismatch");
            } else {
              Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
            }
            try (var insert =
                connection.prepareStatement(
                    "INSERT INTO media_variant(release_id,media_id,variant,object_key,sha256,byte_length,width,height,content_type) VALUES(?,?,?,?,?,?,?,?,?)")) {
              insert.setString(1, "demo-20261018-v1");
              insert.setString(2, parts[0]);
              insert.setString(3, variant);
              insert.setString(4, key);
              insert.setString(5, sha);
              insert.setLong(6, bytes.length);
              insert.setInt(7, image.getWidth());
              insert.setInt(8, image.getHeight());
              insert.setString(9, "image/png");
              insert.executeUpdate();
              count++;
            }
          }
        }
        connection.commit();
        System.out.println(
            "EXPLICIT_DEMO_IMPORT_PASS variants=" + count + "; synthetic provenance only");
      } catch (Exception ex) {
        connection.rollback();
        throw ex;
      }
    }
  }
}
