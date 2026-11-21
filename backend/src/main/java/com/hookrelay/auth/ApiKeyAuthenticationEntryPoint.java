package com.hookrelay.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiKeyAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private static final String UNAUTHORIZED_DETAIL = "A valid X-Api-Key header is required.";

  private final ObjectMapper objectMapper;

  public ApiKeyAuthenticationEntryPoint(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        Map.of(
            "type",
            URI.create("about:blank").toString(),
            "title",
            "Unauthorized",
            "status",
            401,
            "detail",
            UNAUTHORIZED_DETAIL,
            "timestamp",
            Instant.now()));
  }
}
