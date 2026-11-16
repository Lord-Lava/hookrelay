package com.hookrelay.endpoint.api;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionResponse(UUID id, UUID endpointId, String eventType, Instant createdAt) {}
