package com.hookrelay.auth;

import java.io.Serial;

public class AuthenticationRequiredException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  public AuthenticationRequiredException(String message) {
    super(message);
  }
}
