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
import org.springframework.security.web.csrf.CsrfFilter;

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
      HttpSecurity http, AdminWebProperties properties, CookieCsrfTokenRepository csrf)
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
                          path.endsWith("/auth/login") || path.endsWith("/auth/logout");
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
                    .anyRequest()
                    .denyAll())
        .build();
  }
}
