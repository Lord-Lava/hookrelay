package com.hookrelay.endpoint.application;

public class EndpointNotFoundException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = 1L;

  public EndpointNotFoundException(String message) {
    super(message);
  }
}
