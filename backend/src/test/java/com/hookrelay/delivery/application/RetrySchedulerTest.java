package com.hookrelay.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RetrySchedulerTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** Returns a fixed value from {@link Random#nextLong(long, long)}. */
  private static final class FixedRandom extends Random {
    private final long value;

    FixedRandom(long value) {
      this.value = value;
    }

    @Override
    public long nextLong(long origin, long bound) {
      return value;
    }
  }

  private RetryScheduler scheduler(
      int baseSeconds, int maxSeconds, Duration deadline, Random random) {
    return new RetryScheduler(baseSeconds, maxSeconds, deadline, random);
  }

  @Test
  void givenAttempt1_whenComputingNextAttempt_thenDelayIsWithinBaseRange() {
    RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));
    Instant next = scheduler.nextAttemptAt(1, null, NOW);
    long delaySeconds = next.getEpochSecond() - NOW.getEpochSecond();
    assertThat(delaySeconds).isGreaterThanOrEqualTo(5L);
    assertThat(delaySeconds).isLessThanOrEqualTo(10L);
  }

  @Test
  void givenIncreasingAttemptNumber_whenComputingBackoff_thenDelayIncreases() {
    RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));
    Instant first = scheduler.nextAttemptAt(1, null, NOW);
    Instant second = scheduler.nextAttemptAt(2, null, NOW);
    Instant third = scheduler.nextAttemptAt(3, null, NOW);
    long firstDelay = first.getEpochSecond() - NOW.getEpochSecond();
    long secondDelay = second.getEpochSecond() - NOW.getEpochSecond();
    long thirdDelay = third.getEpochSecond() - NOW.getEpochSecond();
    assertThat(secondDelay).isGreaterThanOrEqualTo(firstDelay);
    assertThat(thirdDelay).isGreaterThanOrEqualTo(secondDelay);
  }

  @Test
  void givenHighAttemptNumber_whenComputingBackoff_thenDelayIsCappedAtMax() {
    RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));
    Instant highAttempt = scheduler.nextAttemptAt(100, null, NOW);
    long delaySeconds = highAttempt.getEpochSecond() - NOW.getEpochSecond();
    assertThat(delaySeconds).isGreaterThanOrEqualTo(1800L);
    assertThat(delaySeconds).isLessThanOrEqualTo(3600L);
  }

  @Test
  void givenRetryAfterHint_whenComputingNextAttempt_thenDelayHonorsHintWithinCap() {
    RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));
    Instant withRetryAfter = scheduler.nextAttemptAt(1, 300, NOW);
    long delaySeconds = withRetryAfter.getEpochSecond() - NOW.getEpochSecond();
    assertThat(delaySeconds).isGreaterThanOrEqualTo(300L);
    assertThat(delaySeconds).isLessThanOrEqualTo(3600L);
  }

  @Test
  void givenRetryAfterAboveMax_whenComputingNextAttempt_thenDelayIsCappedAtMax() {
    RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));
    Instant withRetryAfter = scheduler.nextAttemptAt(1, 86400, NOW);
    long delaySeconds = withRetryAfter.getEpochSecond() - NOW.getEpochSecond();
    assertThat(delaySeconds).isLessThanOrEqualTo(3600L);
  }

  @Test
  void givenRetryAfterNegative_whenComputingNextAttempt_thenHintIsIgnored() {
    RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));
    Instant withRetryAfter = scheduler.nextAttemptAt(1, -5, NOW);
    long delaySeconds = withRetryAfter.getEpochSecond() - NOW.getEpochSecond();
    assertThat(delaySeconds).isGreaterThanOrEqualTo(5L);
    assertThat(delaySeconds).isLessThanOrEqualTo(10L);
  }

  @Test
  void givenRetryAfterZero_whenComputingNextAttempt_thenHintIsIgnored() {
    RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));
    Instant withRetryAfter = scheduler.nextAttemptAt(1, 0, NOW);
    long delaySeconds = withRetryAfter.getEpochSecond() - NOW.getEpochSecond();
    assertThat(delaySeconds).isGreaterThanOrEqualTo(5L);
    assertThat(delaySeconds).isLessThanOrEqualTo(10L);
  }

  @Test
  void givenRetryAfterHugeValue_whenComputingNextAttempt_thenDoesNotOverflow() {
    RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));
    Instant withRetryAfter = scheduler.nextAttemptAt(1, Integer.MAX_VALUE, NOW);
    long delaySeconds = withRetryAfter.getEpochSecond() - NOW.getEpochSecond();
    assertThat(delaySeconds).isLessThanOrEqualTo(3600L);
  }

  @Test
  void givenShortCycleDeadline_whenComputingNextAttempt_thenReturnsNull() {
    RetryScheduler shortScheduler = new RetryScheduler(10, 3600, Duration.ofSeconds(4));
    Instant result = shortScheduler.nextAttemptAt(1, null, NOW);
    assertThat(result).isNull();
  }

  @Test
  void givenRetryAfterBeyondDeadline_whenComputingNextAttempt_thenReturnsNull() {
    RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofSeconds(60));
    Instant result = scheduler.nextAttemptAt(1, 3600, NOW);
    assertThat(result).isNull();
  }

  @Test
  void givenInjectableRandom_whenComputingNextAttempt_thenReturnsControlledJitter() {
    RetryScheduler scheduler = scheduler(10, 3600, Duration.ofHours(24), new FixedRandom(7));
    Instant next = scheduler.nextAttemptAt(1, null, NOW);
    assertThat(next.getEpochSecond() - NOW.getEpochSecond()).isEqualTo(7L);
  }

  @Test
  void givenInjectableRandomLowerBound_whenComputingNextAttempt_thenReturnsLowerBound() {
    RetryScheduler scheduler = scheduler(10, 3600, Duration.ofHours(24), new FixedRandom(5));
    Instant next = scheduler.nextAttemptAt(1, null, NOW);
    assertThat(next.getEpochSecond() - NOW.getEpochSecond()).isEqualTo(5L);
  }

  @Test
  void givenInjectableRandomUpperBound_whenComputingNextAttempt_thenReturnsUpperBound() {
    RetryScheduler scheduler = scheduler(10, 3600, Duration.ofHours(24), new FixedRandom(10));
    Instant next = scheduler.nextAttemptAt(1, null, NOW);
    assertThat(next.getEpochSecond() - NOW.getEpochSecond()).isEqualTo(10L);
  }

  @Test
  void givenRetryAfterWithInjectableRandom_whenHintIsBelowJitter_thenJitterWins() {
    RetryScheduler scheduler = scheduler(10, 3600, Duration.ofHours(24), new FixedRandom(9));
    Instant next = scheduler.nextAttemptAt(1, 2, NOW);
    assertThat(next.getEpochSecond() - NOW.getEpochSecond()).isEqualTo(9L);
  }

  @Test
  void givenRetryAfterWithInjectableRandom_whenHintIsAboveJitter_thenHintWins() {
    RetryScheduler scheduler = scheduler(10, 3600, Duration.ofHours(24), new FixedRandom(5));
    Instant next = scheduler.nextAttemptAt(1, 300, NOW);
    assertThat(next.getEpochSecond() - NOW.getEpochSecond()).isEqualTo(300L);
  }

  @Test
  void givenInvalidAttemptNumber_whenComputingNextAttempt_thenThrows() {
    assertThatThrownBy(
            () -> new RetryScheduler(10, 3600, Duration.ofHours(24)).nextAttemptAt(0, null, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RetryScheduler(10, 3600, Duration.ofHours(24)).nextAttemptAt(-1, null, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void givenNullNow_whenComputingNextAttempt_thenThrows() {
    RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));
    assertThatThrownBy(() -> scheduler.nextAttemptAt(1, null, null))
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

  @Test
  void givenAttemptNumber63_whenComputingBackoff_thenDoesNotOverflow() {
    RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));
    Instant result = scheduler.nextAttemptAt(63, null, NOW);
    assertThat(result).isNotNull();
    long delaySeconds = result.getEpochSecond() - NOW.getEpochSecond();
    assertThat(delaySeconds).isGreaterThanOrEqualTo(1800L);
    assertThat(delaySeconds).isLessThanOrEqualTo(3600L);
  }

  @Test
  void givenAttemptNumber64_whenComputingBackoff_thenCappedAtMax() {
    RetryScheduler scheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));
    Instant result = scheduler.nextAttemptAt(64, null, NOW);
    assertThat(result).isNotNull();
    long delaySeconds = result.getEpochSecond() - NOW.getEpochSecond();
    assertThat(delaySeconds).isGreaterThanOrEqualTo(1800L);
    assertThat(delaySeconds).isLessThanOrEqualTo(3600L);
  }
}
