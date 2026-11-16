package com.hookrelay.endpoint.api;

import java.time.Instant;
import java.util.UUID;

public record RotateSecretResponse(
    UUID id, UUID projectId, String url, String signingSecret, boolean enabled, Instant updatedAt) {
  @Override
  public String toString() {
    return "RotateSecretResponse[redacted]";
  }
}
