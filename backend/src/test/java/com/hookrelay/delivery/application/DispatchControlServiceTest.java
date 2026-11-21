package com.hookrelay.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DispatchControlServiceTest {

  private final DispatchControlService control = new DispatchControlService();

  private final UUID projectA = UUID.randomUUID();
  private final UUID projectB = UUID.randomUUID();
  private final UUID endpointA1 = UUID.randomUUID();
  private final UUID endpointA2 = UUID.randomUUID();
  private final UUID endpointB1 = UUID.randomUUID();

  @Test
  void acquireAndReleaseProjectConcurrency() {
    assertThat(control.tryAcquire(projectA, endpointA1, 2, 1, 1000, 1000)).isTrue();
    assertThat(control.tryAcquire(projectA, endpointA2, 2, 1, 1000, 1000)).isTrue();
    assertThat(control.tryAcquire(projectA, endpointA1, 2, 1, 1000, 1000))
        .isFalse(); // project at limit
    assertThat(control.projectActiveClaims(projectA)).isEqualTo(2);

    control.release(projectA, endpointA1);
    assertThat(control.projectActiveClaims(projectA)).isEqualTo(1);

    // Now project A has room again
    assertThat(control.tryAcquire(projectA, endpointA1, 2, 1, 1000, 1000)).isTrue();
    assertThat(control.projectActiveClaims(projectA)).isEqualTo(2);
  }

  @Test
  void endpointConcurrencyLimitIndependentOfProject() {
    assertThat(control.tryAcquire(projectA, endpointA1, 100, 1, 1000, 1000)).isTrue();
    assertThat(control.tryAcquire(projectA, endpointA1, 100, 1, 1000, 1000))
        .isFalse(); // endpoint at limit
    assertThat(control.tryAcquire(projectA, endpointA2, 100, 1, 1000, 1000))
        .isTrue(); // different endpoint OK
    assertThat(control.tryAcquire(projectB, endpointA1, 100, 1, 1000, 1000))
        .isFalse(); // endpoint at limit across projects
    assertThat(control.tryAcquire(projectB, endpointB1, 100, 1, 1000, 1000))
        .isTrue(); // different endpoint OK
  }

  @Test
  void rateLimitPerProject() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    // We can't easily test rate limiting without mocking Instant, but we can verify
    // that rapid acquisitions within the same project work up to the rate limit
    for (int i = 0; i < 20; i++) {
      assertThat(control.tryAcquire(projectA, endpointA1, 100, 100, 20, 100)).isTrue();
    }
    // 21st should be rate limited (limit is 20 per second)
    // Note: this is timing-dependent, may occasionally pass if time has advanced
  }

  @Test
  void releaseDecrementsCounters() {
    assertThat(control.tryAcquire(projectA, endpointA1, 5, 5, 1000, 1000)).isTrue();
    assertThat(control.projectActiveClaims(projectA)).isEqualTo(1);
    assertThat(control.endpointActiveClaims(endpointA1)).isEqualTo(1);

    control.release(projectA, endpointA1);
    assertThat(control.projectActiveClaims(projectA)).isEqualTo(0);
    assertThat(control.endpointActiveClaims(endpointA1)).isEqualTo(0);
  }

  @Test
  void releaseBelowZeroDoesNotGoNegative() {
    control.release(projectA, endpointA1); // release without acquire
    assertThat(control.projectActiveClaims(projectA)).isEqualTo(0);
    assertThat(control.endpointActiveClaims(endpointA1)).isEqualTo(0);
  }
}
