package com.hookrelay.endpoint.application;

import java.io.Serial;

public class SubscriptionNotFoundException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  public SubscriptionNotFoundException(String message) {
    super(message);
  }
}
