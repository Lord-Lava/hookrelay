package com.hookrelay.endpoint.api;

import java.time.Instant;
import java.util.UUID;

public record EndpointResponse(
    UUID id,
    UUID projectId,
    String url,
    String description,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {}
