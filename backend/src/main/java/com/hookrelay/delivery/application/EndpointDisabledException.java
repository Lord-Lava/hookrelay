package com.hookrelay.delivery.application;

public class EndpointDisabledException extends RuntimeException {
  public EndpointDisabledException(String message) {
    super(message);
  }
}
