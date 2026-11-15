package com.hookrelay.event.application;

import java.io.Serial;

public class IdempotencyConflictException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  public IdempotencyConflictException() {
    super("This Idempotency-Key was already used with a different event type or payload.");
  }
}
