package com.hookrelay.common;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {
  private static final int DEFAULT_READ_TIMEOUT_MILLIS = 10_000;

  private final ObjectMapper objectMapper;
  private final int maxRequestBytes;

  public RequestBodySizeLimitFilter(
      ObjectMapper objectMapper,
      @Value("${hookrelay.http.max-request-bytes:270336}") int maxRequestBytes) {
    this.objectMapper = objectMapper;
    if (maxRequestBytes < 1 || maxRequestBytes > 1048576)
      throw new IllegalArgumentException("Request limit must be 1-1048576 bytes");
    this.maxRequestBytes = maxRequestBytes;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      jakarta.servlet.FilterChain filterChain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > maxRequestBytes) {
      writeTooLarge(response);
      return;
    }

    byte[] body = request.getInputStream().readNBytes(maxRequestBytes + 1);
    if (body.length > maxRequestBytes) {
      writeTooLarge(response);
      return;
    }

    filterChain.doFilter(new CachedBodyRequest(request, body), response);
  }

  private void writeTooLarge(HttpServletResponse response) throws IOException {
    response.setStatus(413);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        Map.of(
            "type",
            "about:blank",
            "title",
            "Payload Too Large",
            "status",
            413,
            "detail",
            "Request body exceeds the configured maximum of " + maxRequestBytes + " bytes.",
            "timestamp",
            Instant.now()));
  }

  private static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    private ServletInputStream inputStream;
    private BufferedReader reader;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      if (reader != null) {
        throw new IllegalStateException("getReader() has already been called for this request");
      }
      if (inputStream == null) {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        inputStream =
            new ServletInputStream() {
              @Override
              public int read() {
                return input.read();
              }

              @Override
              public boolean isFinished() {
                return input.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener readListener) {
                throw new IllegalStateException(
                    "Asynchronous request-body reads are not supported by this filter");
              }
            };
      }
      return inputStream;
    }

    @Override
    public BufferedReader getReader() {
      if (inputStream != null) {
        throw new IllegalStateException(
            "getInputStream() has already been called for this request");
      }
      if (reader == null) {
        reader =
            new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
      }
      return reader;
    }
  }
}
