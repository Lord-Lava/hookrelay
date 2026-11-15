package com.hookrelay.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Strategy for handling authentication failures. Follows the Spring Security {@code
 * AuthenticationEntryPoint} contract without depending on the Spring Security framework.
 */
public interface AuthenticationEntryPoint {

  /**
   * Called when authentication fails and the response must be sent to the client.
   *
   * @param request the HTTP request
   * @param response the HTTP response
   * @throws IOException if an I/O error occurs while writing the response
   */
  void commence(HttpServletRequest request, HttpServletResponse response) throws IOException;
}
