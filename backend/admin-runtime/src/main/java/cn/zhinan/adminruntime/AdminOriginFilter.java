package cn.zhinan.adminruntime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects browser writes before CSRF/KDF/session work unless Origin exactly matches the local
 * contract.
 */
final class AdminOriginFilter extends OncePerRequestFilter {
  private final String expectedOrigin;

  AdminOriginFilter(AdminWebProperties properties) {
    this.expectedOrigin = properties.origin().toString();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!HttpMethod.GET.matches(request.getMethod())
        && !HttpMethod.HEAD.matches(request.getMethod())) {
      String origin = request.getHeader("Origin");
      if (!expectedOrigin.equals(origin)) {
        AdminHttpResponses.writeError(
            response,
            HttpServletResponse.SC_FORBIDDEN,
            "ORIGIN_REJECTED",
            "The request origin is not allowed.");
        return;
      }
      if (!request.isSecure()) {
        AdminHttpResponses.writeError(
            response,
            HttpServletResponse.SC_FORBIDDEN,
            "ORIGIN_REJECTED",
            "HTTPS is required for administrative writes.");
        return;
      }
      if (HttpMethod.POST.matches(request.getMethod())) {
        if (!jsonUtf8(request.getContentType())
            || (request.getHeader("Content-Encoding") != null
                && !request.getHeader("Content-Encoding").isBlank()
                && !"identity".equalsIgnoreCase(request.getHeader("Content-Encoding").trim()))) {
          AdminHttpResponses.writeError(
              response,
              HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
              "UNSUPPORTED_MEDIA_TYPE",
              "Only application/json is accepted.");
          return;
        }
        if (request.getContentLengthLong() > 4096) {
          AdminHttpResponses.writeError(
              response, 413, "PAYLOAD_TOO_LARGE", "The request body exceeds 4 KiB.");
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }

  private static boolean jsonUtf8(String value) {
    if (value == null) return false;
    String[] parts = value.split(";", -1);
    if (!"application/json".equalsIgnoreCase(parts[0].trim())) return false;
    for (int i = 1; i < parts.length; i++) {
      String parameter = parts[i].trim();
      if (parameter.toLowerCase(Locale.ROOT).startsWith("charset=")) {
        String charset = parameter.substring("charset=".length()).replace("\"", "").trim();
        if (!"utf-8".equalsIgnoreCase(charset)) return false;
      }
    }
    return true;
  }
}
