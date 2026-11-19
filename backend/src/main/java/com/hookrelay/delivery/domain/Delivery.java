package com.hookrelay.delivery.domain;

import java.time.Instant;
import java.util.UUID;

/** Internal persistence model; never expose destination or internal lease details as an API DTO. */
public record Delivery(
    UUID id,
    UUID projectId,
    UUID eventId,
    UUID endpointId,
    String destinationUrl,
    DeliveryStatus status,
    int attemptCount,
    int cycleNumber,
    int cycleAttemptCount,
    Instant nextAttemptAt,
    String leaseOwner,
    UUID leaseToken,
    Instant leaseExpiresAt,
    Instant cycleStartedAt,
    Instant cycleDeadline,
    String policyVersion,
    int maxAttempts,
    Instant terminalAt,
    String lastErrorCode,
    Instant createdAt,
    Instant updatedAt) {
  @Override
  public String toString() {
    return "Delivery[id=" + id + ", status=" + status + "]";
  }
}
