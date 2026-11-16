package com.hookrelay.endpoint.domain;

import java.time.Instant;
import java.util.UUID;

public record WebhookEndpoint(
    UUID id,
    UUID projectId,
    String url,
    String description,
    String signingSecretCiphertext,
    byte[] nonce,
    String keyVersion,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {}
