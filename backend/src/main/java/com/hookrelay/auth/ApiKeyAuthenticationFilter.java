package com.hookrelay.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  private final ApiKeyAuthService apiKeyAuthService;
  private final AuthenticationEntryPoint authenticationEntryPoint;
  private final ProjectRateLimiter rateLimiter;
  private final tools.jackson.databind.ObjectMapper mapper;

  public ApiKeyAuthenticationFilter(
      ApiKeyAuthService apiKeyAuthService,
      AuthenticationEntryPoint authenticationEntryPoint,
      ProjectRateLimiter rateLimiter,
      tools.jackson.databind.ObjectMapper mapper) {
    this.apiKeyAuthService = apiKeyAuthService;
    this.authenticationEntryPoint = authenticationEntryPoint;
    this.rateLimiter = rateLimiter;
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String rawKey = request.getHeader("X-Api-Key");
    var identity = apiKeyAuthService.authenticate(rawKey);
    if (identity.isEmpty()) {
      authenticationEntryPoint.commence(request, response);
      return;
    }

    int retryAfter = rateLimiter.acquire(identity.get().projectId());
    if (retryAfter > 0) {
      response.setStatus(429);
      response.setHeader("Retry-After", Integer.toString(retryAfter));
      response.setHeader("Cache-Control", "no-store");
      response.setContentType("application/problem+json");
      mapper.writeValue(
          response.getOutputStream(),
          java.util.Map.of(
              "type",
              "about:blank",
              "title",
              "Too Many Requests",
              "status",
              429,
              "detail",
              "Project request limit exceeded",
              "timestamp",
              java.time.Instant.now()));
      return;
    }
    TenantContext.set(identity.get());
    try {
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }
}
