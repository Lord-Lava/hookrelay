package com.hookrelay.endpoint.application;

public class SubscriptionNotFoundException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = 1L;

  public SubscriptionNotFoundException(String message) {
    super(message);
  }
}
