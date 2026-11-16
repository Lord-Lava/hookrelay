package com.hookrelay.endpoint.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookEndpointTest {

  @Test
  void isImmutableRecord() {
    UUID id = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    Instant now = Instant.parse("2026-11-15T12:00:00Z");

    WebhookEndpoint endpoint =
        new WebhookEndpoint(
            id,
            projectId,
            "https://example.com/webhook",
            "My endpoint",
            "ciphertext",
            new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
            "v1",
            true,
            now,
            now,
            null);

    assertThat(endpoint.id()).isEqualTo(id);
    assertThat(endpoint.projectId()).isEqualTo(projectId);
    assertThat(endpoint.url()).isEqualTo("https://example.com/webhook");
    assertThat(endpoint.description()).isEqualTo("My endpoint");
    assertThat(endpoint.signingSecretCiphertext()).isEqualTo("ciphertext");
    assertThat(endpoint.enabled()).isTrue();
    assertThat(endpoint.createdAt()).isEqualTo(now);
    assertThat(endpoint.updatedAt()).isEqualTo(now);
    assertThat(endpoint.deletedAt()).isNull();
  }

  @Test
  void supportsSoftDeletedState() {
    Instant deletedAt = Instant.parse("2026-11-15T13:00:00Z");

    WebhookEndpoint endpoint =
        new WebhookEndpoint(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "https://example.com/webhook",
            null,
            null,
            new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
            "v1",
            false,
            Instant.now(),
            Instant.now(),
            deletedAt);

    assertThat(endpoint.enabled()).isFalse();
    assertThat(endpoint.deletedAt()).isEqualTo(deletedAt);
    assertThat(endpoint.signingSecretCiphertext()).isNull();
  }

  @Test
  void equalsAndHashCodeAreConsistent() {
    UUID id = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    Instant now = Instant.now();
    Instant deletedAt = null;
    byte[] nonce = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    WebhookEndpoint a =
        new WebhookEndpoint(
            id,
            projectId,
            "https://example.com/webhook",
            null,
            null,
            nonce,
            "v1",
            true,
            now,
            now,
            deletedAt);
    WebhookEndpoint b =
        new WebhookEndpoint(
            id,
            projectId,
            "https://example.com/webhook",
            null,
            null,
            nonce,
            "v1",
            true,
            now,
            now,
            deletedAt);

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  void recordsWithDifferentIdsAreNotEqual() {
    WebhookEndpoint a =
        new WebhookEndpoint(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "https://example.com/webhook",
            null,
            null,
            new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
            "v1",
            true,
            Instant.now(),
            Instant.now(),
            null);
    WebhookEndpoint b =
        new WebhookEndpoint(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "https://example.com/webhook",
            null,
            null,
            new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
            "v1",
            true,
            Instant.now(),
            Instant.now(),
            null);

    assertThat(a).isNotEqualTo(b);
  }
}
