package com.hookrelay.event.application;

public class IdempotencyConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public IdempotencyConflictException() {
    super("This Idempotency-Key was already used with a different event type or payload.");
  }
}
