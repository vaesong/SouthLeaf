package cn.zhinan.adminruntime;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal safe JSON error writer for the servlet security filters, which run before MVC. */
final class AdminHttpResponses {
  private AdminHttpResponses() {}

  static void writeError(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    String requestId = UUID.randomUUID().toString();
    response.setStatus(status);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Request-Id", requestId);
    response.setHeader("X-Content-Type-Options", "nosniff");
    response
        .getWriter()
        .write(
            "{\"code\":\""
                + escape(code)
                + "\",\"message\":\""
                + escape(message)
                + "\",\"requestId\":\""
                + requestId
                + "\"}");
  }

  private static String escape(String value) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c));
          else escaped.append(c);
        }
      }
    }
    return escaped.toString();
  }
}
