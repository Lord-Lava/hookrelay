package com.hookrelay.event.application;

public class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException() {
    super("This Idempotency-Key was already used with a different event type or payload.");
  }
}
