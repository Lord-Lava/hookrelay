package com.hookrelay.auth;

public class AuthenticationRequiredException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = 1L;

  public AuthenticationRequiredException(String message) {
    super(message);
  }
}
