package cn.zhinan.adminruntime;

import static org.springframework.security.config.Customizer.withDefaults;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * S1-D browser defaults. This is activated only for an explicitly enabled servlet candidate; the
 * normal runtime remains non-Web.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
class AdminWebSecurityConfiguration {
  @Bean
  CookieCsrfTokenRepository adminCsrfTokenRepository() {
    var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookieName(AdminAuthController.CSRF_COOKIE);
    csrf.setHeaderName("X-CSRF-TOKEN");
    csrf.setCookieCustomizer(
        cookie -> cookie.secure(true).sameSite("Strict").path("/").domain(null));
    return csrf;
  }

  @Bean
  SecurityFilterChain adminWebSecurityFilterChain(
      HttpSecurity http,
      AdminWebProperties properties,
      AdminRuntimeProperties runtime,
      CookieCsrfTokenRepository csrf)
      throws Exception {
    if (!properties.enabled())
      throw new AdminStartupException(AdminStartupException.Reason.CONFIGURATION);
    return http.securityMatcher("/api/admin/v1/**")
        .csrf(csrfConfig -> csrfConfig.csrfTokenRepository(csrf))
        .addFilterBefore(new AdminOriginFilter(properties), CsrfFilter.class)
        .cors(AbstractHttpConfigurer::disable)
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .securityContext(withDefaults())
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .rememberMe(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            errors ->
                errors.accessDeniedHandler(
                    (request, response, denied) -> {
                      String path = request.getRequestURI();
                      boolean writeAuthPath =
                          path.endsWith("/auth/login")
                              || path.endsWith("/auth/logout")
                              || denied instanceof CsrfException;
                      AdminHttpResponses.writeError(
                          response,
                          HttpServletResponse.SC_FORBIDDEN,
                          writeAuthPath ? "CSRF_REJECTED" : "ACCESS_DENIED",
                          writeAuthPath
                              ? "The request security check failed."
                              : "Access is denied.");
                    }))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        HttpMethod.GET, "/api/admin/v1/auth/csrf", "/api/admin/v1/auth/session")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST, "/api/admin/v1/auth/login", "/api/admin/v1/auth/logout")
                    .permitAll()
                    // Content routes exist only when enabled; the controller re-authenticates
                    // the session and checks CONTENT_* capabilities on every request.
                    .requestMatchers(contentRoutes(runtime))
                    .permitAll()
                    .requestMatchers(outboxRoutes(runtime))
                    .permitAll()
                    .requestMatchers(bookingRoutes(runtime))
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .build();
  }

  private static RequestMatcher outboxRoutes(AdminRuntimeProperties runtime) {
    if (!runtime.outbox().enabled()) return request -> false;
    var routes = PathPatternRequestMatcher.withDefaults();
    return new OrRequestMatcher(
        routes.matcher(HttpMethod.GET, "/api/admin/v1/notifications/outbox"),
        routes.matcher(HttpMethod.POST, "/api/admin/v1/notifications/outbox/{messageId}/recover"));
  }

  private static RequestMatcher bookingRoutes(AdminRuntimeProperties runtime) {
    if (!runtime.booking().enabled()) return request -> false;
    var routes = PathPatternRequestMatcher.withDefaults();
    return new OrRequestMatcher(
        routes.matcher(HttpMethod.GET, "/api/admin/v1/bookings/reservations/pending"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/bookings/reservations/{reservationId}"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/bookings/slots"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/bookings/orders/{orderId}"),
        routes.matcher(
            HttpMethod.POST, "/api/admin/v1/bookings/reservations/{reservationId}/cancel"),
        routes.matcher(
            HttpMethod.POST, "/api/admin/v1/bookings/reservations/{reservationId}/reschedule"),
        routes.matcher(
            HttpMethod.POST, "/api/admin/v1/bookings/reservations/{reservationId}/confirm"));
  }

  private static RequestMatcher contentRoutes(AdminRuntimeProperties runtime) {
    if (!runtime.content().enabled()) return request -> false;
    var routes = PathPatternRequestMatcher.withDefaults();
    return new OrRequestMatcher(
        routes.matcher(HttpMethod.GET, "/api/admin/v1/media"),
        routes.matcher(HttpMethod.POST, "/api/admin/v1/media/uploads"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/media/{mediaId}"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/media/{mediaId}/{variant}/content"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/works"),
        routes.matcher(HttpMethod.POST, "/api/admin/v1/works"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/works/{workId}"),
        routes.matcher(HttpMethod.PUT, "/api/admin/v1/works/{workId}"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/works/{workId}/preview"),
        routes.matcher(HttpMethod.POST, "/api/admin/v1/works/{workId}/publish"),
        routes.matcher(HttpMethod.POST, "/api/admin/v1/works/{workId}/withdraw"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/content/catalog"),
        routes.matcher(HttpMethod.PUT, "/api/admin/v1/content/catalog"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/content/catalog/preview"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/content/catalog/versions"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/content/catalog/versions/{revision}"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/content/catalog/commands"),
        routes.matcher(HttpMethod.POST, "/api/admin/v1/content/catalog/publish"),
        routes.matcher(HttpMethod.POST, "/api/admin/v1/content/catalog/withdraw"),
        routes.matcher(HttpMethod.GET, "/api/admin/v1/commands/{commandId}"));
  }
}
