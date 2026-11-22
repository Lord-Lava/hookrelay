package com.hookrelay.delivery.application;

public class InvalidDeliveryStateException extends RuntimeException {
  public InvalidDeliveryStateException(String message) {
    super(message);
  }
}
