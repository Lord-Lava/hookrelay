package com.hookrelay.delivery.domain;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttempt(
    UUID id,
    UUID projectId,
    UUID deliveryId,
    int attemptNumber,
    int cycleNumber,
    UUID leaseToken,
    Instant startedAt,
    Instant completedAt,
    AttemptOutcome outcome,
    Integer httpStatus,
    Long durationMs,
    String errorCode) {}
