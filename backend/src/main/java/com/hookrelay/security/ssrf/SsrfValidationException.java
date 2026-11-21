package com.hookrelay.security.ssrf;

import java.io.Serial;

public class SsrfValidationException extends IllegalArgumentException {
  @Serial private static final long serialVersionUID = 1L;

  public SsrfValidationException(String message) {
    super(message);
  }

  public SsrfValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
