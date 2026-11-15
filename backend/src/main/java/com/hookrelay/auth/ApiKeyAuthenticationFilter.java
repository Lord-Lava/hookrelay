package com.hookrelay.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
  private final ApiKeyAuthService apiKeyAuthService;
  private final ObjectMapper objectMapper;

  public ApiKeyAuthenticationFilter(
      ApiKeyAuthService apiKeyAuthService, ObjectMapper objectMapper) {
    this.apiKeyAuthService = apiKeyAuthService;
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String rawKey = request.getHeader("X-Api-Key");
    var identity = apiKeyAuthService.authenticate(rawKey);
    if (identity.isEmpty()) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      objectMapper.writeValue(
          response.getOutputStream(),
          Map.of(
              "type", "about:blank",
              "title", "Unauthorized",
              "status", 401,
              "detail", "A valid X-Api-Key header is required."));
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
