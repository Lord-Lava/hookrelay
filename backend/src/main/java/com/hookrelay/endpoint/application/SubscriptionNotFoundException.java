package com.hookrelay.endpoint.application;

public class SubscriptionNotFoundException extends RuntimeException {

  public SubscriptionNotFoundException(String message) {
    super(message);
  }
}
