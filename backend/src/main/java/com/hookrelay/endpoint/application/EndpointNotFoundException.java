package com.hookrelay.endpoint.application;

public class EndpointNotFoundException extends RuntimeException {

  public EndpointNotFoundException(String message) {
    super(message);
  }
}
