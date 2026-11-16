package com.hookrelay.security;

public class SigningUnavailableException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = 1L;

  public SigningUnavailableException() {
    super("Signing service is not configured");
  }
}
