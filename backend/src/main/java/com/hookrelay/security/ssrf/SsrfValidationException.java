package com.hookrelay.security.ssrf;

public class SsrfValidationException extends IllegalArgumentException {
  @java.io.Serial private static final long serialVersionUID = 1L;

  public SsrfValidationException(String message) {
    super(message);
  }

  public SsrfValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
