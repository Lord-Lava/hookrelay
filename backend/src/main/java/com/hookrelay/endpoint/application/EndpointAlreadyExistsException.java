package com.hookrelay.endpoint.application;

public class EndpointAlreadyExistsException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = 1L;

  public EndpointAlreadyExistsException(String message) {
    super(message);
  }

  public EndpointAlreadyExistsException(String message, Throwable cause) {
    super(message, cause);
  }
}
