package com.hookrelay.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetrySchedulerTest {

  private final RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));

  @Test
  void givenAttempt1_whenComputingNextAttempt_thenDelayIsWithinBaseRange() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Instant next = scheduler.nextAttemptAt(1, null, now);
    long delaySeconds = next.getEpochSecond() - now.getEpochSecond();
    assertThat(delaySeconds).isGreaterThanOrEqualTo(5L); // cap/2 = 5
    assertThat(delaySeconds).isLessThanOrEqualTo(10L); // cap = 10
  }

  @Test
  void givenIncreasingAttemptNumber_whenComputingBackoff_thenDelayIncreases() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Instant first = scheduler.nextAttemptAt(1, null, now);
    Instant second = scheduler.nextAttemptAt(2, null, now);
    Instant third = scheduler.nextAttemptAt(3, null, now);
    long firstDelay = first.getEpochSecond() - now.getEpochSecond();
    long secondDelay = second.getEpochSecond() - now.getEpochSecond();
    long thirdDelay = third.getEpochSecond() - now.getEpochSecond();
    assertThat(secondDelay).isGreaterThanOrEqualTo(firstDelay);
    assertThat(thirdDelay).isGreaterThanOrEqualTo(secondDelay);
  }

  @Test
  void givenHighAttemptNumber_whenComputingBackoff_thenDelayIsCappedAtMax() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Instant highAttempt = scheduler.nextAttemptAt(100, null, now);
    long delaySeconds = highAttempt.getEpochSecond() - now.getEpochSecond();
    assertThat(delaySeconds).isGreaterThanOrEqualTo(1800L); // cap/2 = 1800
    assertThat(delaySeconds).isLessThanOrEqualTo(3600L); // cap = 3600
  }

  @Test
  void givenRetryAfterHint_whenComputingNextAttempt_thenDelayHonorsHintWithinCap() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Instant withRetryAfter = scheduler.nextAttemptAt(1, 300, now);
    long delaySeconds = withRetryAfter.getEpochSecond() - now.getEpochSecond();
    assertThat(delaySeconds).isGreaterThanOrEqualTo(300L);
    assertThat(delaySeconds).isLessThanOrEqualTo(3600L);
  }

  @Test
  void givenRetryAfterAboveMax_whenComputingNextAttempt_thenDelayIsCappedAtMax() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Instant withRetryAfter = scheduler.nextAttemptAt(1, 86400, now);
    long delaySeconds = withRetryAfter.getEpochSecond() - now.getEpochSecond();
    assertThat(delaySeconds).isLessThanOrEqualTo(3600L);
  }

  @Test
  void givenShortCycleDeadline_whenComputingNextAttempt_thenReturnsNull() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RetryScheduler shortScheduler = new RetryScheduler(10, 3600, Duration.ofSeconds(4));
    Instant result = shortScheduler.nextAttemptAt(1, null, now);
    assertThat(result).isNull();
  }

  @Test
  void givenInvalidAttemptNumber_whenComputingNextAttempt_thenThrows() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    assertThatThrownBy(() -> scheduler.nextAttemptAt(0, null, now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> scheduler.nextAttemptAt(-1, null, now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void givenInvalidConfiguration_whenConstructing_thenThrows() {
    assertThatThrownBy(() -> new RetryScheduler(0, 3600, Duration.ofHours(24)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryScheduler(10, 5, Duration.ofHours(24)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryScheduler(10, 3600, Duration.ofSeconds(0)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
