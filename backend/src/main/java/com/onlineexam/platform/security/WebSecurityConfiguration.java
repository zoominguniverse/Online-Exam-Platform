package com.onlineexam.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.platform.controller.ApiResponse;
import com.onlineexam.platform.repository.SessionRepository;
import com.onlineexam.platform.service.IdentityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WebSecurityConfiguration {
    @Bean
    CookieCsrfTokenRepository csrfTokens() {
        var tokens = new CookieCsrfTokenRepository();
        tokens.setCookieName("__Host-exam_csrf");
        tokens.setCookieCustomizer(cookie -> cookie.path("/").secure(true).httpOnly(true).sameSite("Lax"));
        return tokens;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SessionRepository sessions, Clock clock,
                                    ObjectMapper json, CookieCsrfTokenRepository csrfTokens) throws Exception {
        return http.sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout.disable())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, error) ->
                                failure(json, res, 401, "UNAUTHENTICATED", "请登录后继续。"))
                        .accessDeniedHandler((req, res, error) ->
                                failure(json, res, 403, "FORBIDDEN", "没有访问权限。")))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected boolean shouldNotFilter(HttpServletRequest request) {
                        String path = request.getServletPath();
                        return !path.startsWith("/api/v1/") || path.equals("/api/v1/version")
                                || path.equals("/api/v1/auth/csrf");
                    }

                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws IOException, ServletException {
                        try {
                            principal(request, sessions, OffsetDateTime.now(clock)).ifPresent(principal -> {
                                request.setAttribute("exam.actor",principal.id());
                                org.slf4j.MDC.put("actorId",principal.id().toString());
                                var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
                                var context = SecurityContextHolder.createEmptyContext();
                                context.setAuthentication(authentication);
                                SecurityContextHolder.setContext(context);
                            });
                        } catch (DataAccessException error) {
                            failure(json, response, 503, "DEPENDENCY_UNAVAILABLE", "数据服务暂不可用。");
                            return;
                        }
                        chain.doFilter(request, response);
                    }
                }, CsrfFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/version", "/api/v1/auth/csrf",
                                "/api/v1/catalog/grades").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/attachments").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/attachments/*").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET,"/api/v1/me/subjects","/api/v1/me/attempts","/api/v1/me/attempts/*/review","/api/v1/me/mistakes","/api/v1/me/mistakes/*").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.POST,"/api/v1/me/attempts/*/self-grade").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET,"/api/v1/me/attempts/*/questions/*/attachments/*").hasRole("STUDENT")
                        .requestMatchers("/api/v1/me/profile","/api/v1/me/avatar","/api/v1/me/avatar/image","/api/v1/me/messages","/api/v1/me/messages/*","/api/v1/me/messages/*/read","/api/v1/me/activities").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/me/papers", "/api/v1/me/papers/*").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.POST, "/api/v1/me/papers/*/attempts").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/v1/me/attempts/*").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/me/attempts/*/answers").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.POST, "/api/v1/me/attempts/*/submit").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/v1/me/tasks", "/api/v1/me/tasks/*").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.POST, "/api/v1/me/tasks/*/papers/*/attempts").hasRole("STUDENT")
                        .requestMatchers("/api/v1/me", "/api/v1/me/password", "/api/v1/auth/logout").authenticated()
                        .anyRequest().denyAll()).build();
    }

    private static Optional<SessionRepository.Principal> principal(HttpServletRequest request,
            SessionRepository sessions, OffsetDateTime now) {
        // Current authentication accepts only the HttpOnly Web session cookie.
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) return Optional.empty();
        String cookie = WebSession.token(request);
        return IdentityService.validToken(cookie) ? sessions.find(IdentityService.digest(cookie), now) : Optional.empty();
    }

    private static void failure(ObjectMapper json, HttpServletResponse response, int status,
                                String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
