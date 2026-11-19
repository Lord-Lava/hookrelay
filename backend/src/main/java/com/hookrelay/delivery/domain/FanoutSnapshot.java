package com.hookrelay.delivery.domain;

import java.time.Instant;
import java.util.UUID;

/** Defensive copies prevent callers changing the bytes that will eventually be signed. */
public record FanoutSnapshot(
    UUID projectId,
    UUID eventId,
    Instant snapshotAt,
    int recipientCount,
    String bodyVersion,
    byte[] body) {
  public FanoutSnapshot {
    body = body.clone();
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  @Override
  public String toString() {
    return "FanoutSnapshot[eventId=" + eventId + ", recipients=" + recipientCount + "]";
  }
}
