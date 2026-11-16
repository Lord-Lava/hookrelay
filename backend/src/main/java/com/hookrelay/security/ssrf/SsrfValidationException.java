package com.hookrelay.security.ssrf;

public class SsrfValidationException extends IllegalArgumentException {

  public SsrfValidationException(String message) {
    super(message);
  }

  public SsrfValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
