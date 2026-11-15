package com.hookrelay.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  private final ApiKeyAuthService apiKeyAuthService;
  private final AuthenticationEntryPoint authenticationEntryPoint;

  public ApiKeyAuthenticationFilter(
      ApiKeyAuthService apiKeyAuthService, AuthenticationEntryPoint authenticationEntryPoint) {
    this.apiKeyAuthService = apiKeyAuthService;
    this.authenticationEntryPoint = authenticationEntryPoint;
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
      authenticationEntryPoint.commence(request, response);
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
