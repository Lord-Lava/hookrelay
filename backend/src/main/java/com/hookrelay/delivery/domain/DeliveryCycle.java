package com.hookrelay.delivery.domain;

import java.time.Instant;
import java.util.UUID;

public record DeliveryCycle(
    UUID deliveryId,
    int number,
    Instant startedAt,
    Instant deadline,
    String policyVersion,
    int maxAttempts,
    DeliveryStatus terminalStatus,
    Instant terminalAt) {}
