package com.hookrelay.endpoint.domain;

import java.time.Instant;
import java.util.UUID;

public record EndpointSubscription(
    UUID id, UUID endpointId, String eventType, Instant createdAt, Instant deletedAt) {}
