package com.hookrelay.endpoint.application;

public class EndpointAlreadyExistsException extends RuntimeException {

  public EndpointAlreadyExistsException(String message) {
    super(message);
  }

  public EndpointAlreadyExistsException(String message, Throwable cause) {
    super(message, cause);
  }
}
