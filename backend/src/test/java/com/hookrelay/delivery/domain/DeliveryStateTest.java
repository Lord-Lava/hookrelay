package com.hookrelay.delivery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryStateTest {
  @Test
  void permitsOnlyDocumentedTransitions() {
    var expected =
        Map.of(
            DeliveryStatus.PENDING,
                Set.of(
                    DeliveryStatus.IN_PROGRESS,
                    DeliveryStatus.CANCELLED,
                    DeliveryStatus.DEAD_LETTERED),
            DeliveryStatus.RETRY_SCHEDULED,
                Set.of(
                    DeliveryStatus.IN_PROGRESS,
                    DeliveryStatus.CANCELLED,
                    DeliveryStatus.DEAD_LETTERED),
            DeliveryStatus.IN_PROGRESS,
                Set.of(
                    DeliveryStatus.DELIVERED,
                    DeliveryStatus.RETRY_SCHEDULED,
                    DeliveryStatus.CANCELLED,
                    DeliveryStatus.DEAD_LETTERED),
            DeliveryStatus.DEAD_LETTERED, Set.of(DeliveryStatus.PENDING),
            DeliveryStatus.DELIVERED, Set.<DeliveryStatus>of(),
            DeliveryStatus.CANCELLED, Set.<DeliveryStatus>of());
    for (var from : DeliveryStatus.values())
      for (var to : DeliveryStatus.values()) {
        assertThat(from.canTransitionTo(to))
            .as("%s -> %s", from, to)
            .isEqualTo(expected.get(from).contains(to));
        if (!expected.get(from).contains(to))
          assertThatThrownBy(() -> from.requireTransitionTo(to))
              .isInstanceOf(IllegalStateException.class);
      }
  }

  @Test
  void rejectsImpossibleAttemptResultsAndUnsafePolicies() {
    assertThatThrownBy(() -> AttemptOutcome.SUCCEEDED.validateResult(500, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AttemptOutcome.UNKNOWN.validateResult(200, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AttemptOutcome.STARTED.validateResult(null, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AttemptOutcome.PERMANENT_FAILURE.validateResult(400, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DeliveryPolicy("v1", 0, Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DeliveryPolicy("v1", 8, Duration.ofDays(2)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void snapshotBytesCannotBeMutatedOrLogged() {
    byte[] bytes = "{\"private\":true}".getBytes(StandardCharsets.UTF_8);
    var snapshot =
        new FanoutSnapshot(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), 0, "v1", bytes);
    byte[] original = bytes.clone();
    bytes[0] = 0;
    snapshot.body()[1] = 0;
    assertThat(snapshot.body()).isEqualTo(original);
    assertThat(snapshot.toString()).doesNotContain("private");
  }
}
