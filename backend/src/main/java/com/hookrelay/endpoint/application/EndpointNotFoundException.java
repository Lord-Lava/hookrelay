package com.hookrelay.endpoint.application;

import java.io.Serial;

public class EndpointNotFoundException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  public EndpointNotFoundException(String message) {
    super(message);
  }
}
