package cn.zhinan.adminruntime;

import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Serves only the separately built admin-web assets and its same-origin SPA entry routes. */
@RestController
@ConditionalOnWebApplication(type = Type.SERVLET)
class AdminWebUiController {
  @GetMapping("/")
  ResponseEntity<Resource> root() {
    return staticResource("index.html");
  }

  @GetMapping("/{*path}")
  ResponseEntity<?> route(@PathVariable String path) {
    String relative = path.startsWith("/") ? path.substring(1) : path;
    if (relative.equals("api") || relative.startsWith("api/"))
      return ResponseEntity.notFound().build();
    if (relative.equals("index.html")
        || relative.equals("favicon.ico")
        || relative.startsWith("assets/")) return staticResource(relative);
    // The client router owns unknown same-origin pages and renders its explicit 404 state.
    return staticResource("index.html");
  }

  private static ResponseEntity<Resource> staticResource(String relative) {
    if (relative.isBlank()
        || relative.contains("..")
        || relative.contains("\\")
        || !relative.matches("[A-Za-z0-9_./-]+")) return ResponseEntity.notFound().build();
    var resource = new ClassPathResource("static/" + relative);
    if (!resource.exists() || !resource.isReadable()) return ResponseEntity.notFound().build();
    MediaType type =
        MediaTypeFactory.getMediaType(resource)
            .orElse(
                relative.toLowerCase(Locale.ROOT).endsWith(".html")
                    ? MediaType.TEXT_HTML
                    : MediaType.APPLICATION_OCTET_STREAM);
    return ResponseEntity.ok()
        .contentType(type)
        .cacheControl(
            relative.equals("index.html") ? CacheControl.noCache() : CacheControl.noStore())
        .body(resource);
  }
}
