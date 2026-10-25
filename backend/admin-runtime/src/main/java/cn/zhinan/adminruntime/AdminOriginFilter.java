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
 * Rejects browser writes before CSRF/KDF/session work unless the request origin is one the
 * configured policy accepts.
 */
final class AdminOriginFilter extends OncePerRequestFilter {
  static final String UPLOAD_PATH = "/api/admin/v1/media/uploads";
  static final long MAX_UPLOAD_REQUEST_BYTES = 4L * 1024 * 1024 + 64 * 1024;
  private static final long MAX_PUT_BYTES = 16 * 1024;
  private final AdminOriginPolicy originPolicy;

  AdminOriginFilter(AdminOriginPolicy originPolicy) {
    this.originPolicy = originPolicy;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!HttpMethod.GET.matches(request.getMethod())
        && !HttpMethod.HEAD.matches(request.getMethod())) {
      if (!originPolicy.accepts(request.getHeader("Origin"))) {
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
      if (UPLOAD_PATH.equals(request.getRequestURI())
          && HttpMethod.POST.matches(request.getMethod())) {
        // The only non-JSON write: one bounded multipart file; chunked/unknown length is refused.
        String type = request.getContentType();
        if (type == null
            || !type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data;")
            || hasContentEncoding(request)) {
          AdminHttpResponses.writeError(
              response,
              HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
              "MEDIA_TYPE_UNSUPPORTED",
              "Only a single multipart/form-data file is accepted.");
          return;
        }
        long length = request.getContentLengthLong();
        if (length < 0 || length > MAX_UPLOAD_REQUEST_BYTES) {
          AdminHttpResponses.writeError(
              response, 413, "MEDIA_TOO_LARGE", "The upload exceeds the configured limit.");
          return;
        }
        chain.doFilter(request, response);
        return;
      }
      if (HttpMethod.PUT.matches(request.getMethod())) {
        if (!jsonUtf8(request.getContentType()) || hasContentEncoding(request)) {
          AdminHttpResponses.writeError(
              response,
              HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
              "UNSUPPORTED_MEDIA_TYPE",
              "Only application/json is accepted.");
          return;
        }
        if (request.getContentLengthLong() > MAX_PUT_BYTES) {
          AdminHttpResponses.writeError(
              response, 413, "PAYLOAD_TOO_LARGE", "The request body exceeds 16 KiB.");
          return;
        }
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

  private static boolean hasContentEncoding(HttpServletRequest request) {
    String encoding = request.getHeader("Content-Encoding");
    return encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding.trim());
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
