package com.hookrelay.delivery.application;

import java.time.Instant;
import java.util.UUID;

public record EventNotification(
    UUID outboxId, UUID projectId, UUID eventId, String eventType, Instant createdAt) {}
