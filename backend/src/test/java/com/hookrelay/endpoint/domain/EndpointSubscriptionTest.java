package com.hookrelay.endpoint.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EndpointSubscriptionTest {

  @Test
  void isImmutableRecord() {
    UUID id = UUID.randomUUID();
    UUID endpointId = UUID.randomUUID();
    Instant now = Instant.parse("2026-11-15T12:00:00Z");

    EndpointSubscription subscription =
        new EndpointSubscription(id, endpointId, "payment.succeeded", now, null);

    assertThat(subscription.id()).isEqualTo(id);
    assertThat(subscription.endpointId()).isEqualTo(endpointId);
    assertThat(subscription.eventType()).isEqualTo("payment.succeeded");
    assertThat(subscription.createdAt()).isEqualTo(now);
    assertThat(subscription.deletedAt()).isNull();
  }

  @Test
  void supportsSoftDeletedState() {
    Instant deletedAt = Instant.parse("2026-11-15T13:00:00Z");

    EndpointSubscription subscription =
        new EndpointSubscription(
            UUID.randomUUID(), UUID.randomUUID(), "payment.succeeded", Instant.now(), deletedAt);

    assertThat(subscription.deletedAt()).isEqualTo(deletedAt);
  }

  @Test
  void equalsAndHashCodeAreConsistent() {
    UUID id = UUID.randomUUID();
    UUID endpointId = UUID.randomUUID();
    Instant now = Instant.now();

    EndpointSubscription a =
        new EndpointSubscription(id, endpointId, "payment.succeeded", now, null);
    EndpointSubscription b =
        new EndpointSubscription(id, endpointId, "payment.succeeded", now, null);

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  void recordsWithDifferentIdsAreNotEqual() {
    Instant now = Instant.now();

    EndpointSubscription a =
        new EndpointSubscription(
            UUID.randomUUID(), UUID.randomUUID(), "payment.succeeded", now, null);
    EndpointSubscription b =
        new EndpointSubscription(
            UUID.randomUUID(), UUID.randomUUID(), "payment.succeeded", now, null);

    assertThat(a).isNotEqualTo(b);
  }
}
