package com.hookrelay.delivery.domain;

/** Legal domain transitions. Replays require a new audited cycle, not a status-only update. */
public enum DeliveryStatus {
  PENDING,
  IN_PROGRESS,
  RETRY_SCHEDULED,
  DELIVERED,
  DEAD_LETTERED,
  CANCELLED;

  public boolean canTransitionTo(DeliveryStatus next) {
    return switch (this) {
      case PENDING, RETRY_SCHEDULED ->
          next == IN_PROGRESS || next == DEAD_LETTERED || next == CANCELLED;
      case IN_PROGRESS ->
          next == DELIVERED
              || next == RETRY_SCHEDULED
              || next == DEAD_LETTERED
              || next == CANCELLED;
      case DEAD_LETTERED -> next == PENDING;
      case DELIVERED, CANCELLED -> false;
    };
  }

  public void requireTransitionTo(DeliveryStatus next) {
    if (!canTransitionTo(next)) throw new IllegalStateException("Illegal delivery transition");
  }

  public boolean terminal() {
    return this == DELIVERED || this == DEAD_LETTERED || this == CANCELLED;
  }
}
