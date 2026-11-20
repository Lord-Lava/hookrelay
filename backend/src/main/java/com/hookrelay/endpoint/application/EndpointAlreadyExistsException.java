package com.hookrelay.endpoint.application;

import java.io.Serial;

public class EndpointAlreadyExistsException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  public EndpointAlreadyExistsException(String message) {
    super(message);
  }

  public EndpointAlreadyExistsException(String message, Throwable cause) {
    super(message, cause);
  }
}
