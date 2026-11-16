package com.hookrelay.endpoint.api;

import java.time.Instant;
import java.util.UUID;

public record CreatedEndpointResponse(
    UUID id,
    UUID projectId,
    String url,
    String description,
    String signingSecret,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {
  @Override
  public String toString() {
    return "CreatedEndpointResponse[redacted]";
  }
}
