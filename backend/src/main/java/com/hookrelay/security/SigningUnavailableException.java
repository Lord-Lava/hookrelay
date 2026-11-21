package com.hookrelay.security;

import java.io.Serial;

public class SigningUnavailableException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  public SigningUnavailableException() {
    super("Signing service is not configured");
  }
}
