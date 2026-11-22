package com.hookrelay.delivery.application;

public class DeliveryNotFoundException extends RuntimeException {
  public DeliveryNotFoundException(String message) {
    super(message);
  }
}
