package com.hookrelay.delivery.application;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Retry scheduling policy: exponential backoff with equal jitter, bounded Retry-After honoring, and
 * cycle deadline enforcement.
 *
 * <p>For attempt number {@code n} (1-based), the capped backoff is {@code min(maxSeconds,
 * baseSeconds * 2^(n-1))}. Equal jitter selects uniformly from {@code [cap/2, cap]}. Valid
 * Retry-After values are clamped to {@code maxSeconds} and combined by taking the maximum with
 * jittered backoff. All computed {@code nextAttemptAt} values are clamped to the cycle deadline;
 * attempts beyond the deadline are dead-lettered instead of retried.
 */
public class RetryScheduler {

  private final int baseSeconds;
  private final int maxSeconds;
  private final Duration cycleDeadline;
  private final ThreadLocalRandom random = ThreadLocalRandom.current();

  public RetryScheduler(int baseSeconds, int maxSeconds, Duration cycleDeadline) {
    if (baseSeconds < 1
        || baseSeconds > 3600
        || maxSeconds < baseSeconds
        || maxSeconds > 86400
        || cycleDeadline == null
        || cycleDeadline.isNegative()
        || cycleDeadline.isZero()) {
      throw new IllegalArgumentException("Invalid retry scheduler configuration");
    }
    this.baseSeconds = baseSeconds;
    this.maxSeconds = maxSeconds;
    this.cycleDeadline = cycleDeadline;
  }

  /**
   * Computes the next attempt instant for a failed attempt.
   *
   * @param attemptNumber the 1-based attempt number that just failed
   * @param retryAfterSeconds optional Retry-After hint in seconds (null if absent)
   * @param now current instant
   * @return the computed {@code nextAttemptAt}, or {@code null} if the cycle deadline has been
   *     exceeded (caller should dead-letter instead)
   */
  public Instant nextAttemptAt(int attemptNumber, Integer retryAfterSeconds, Instant now) {
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("Attempt number must be positive");
    }
    if (now == null) {
      throw new IllegalArgumentException("Now must not be null");
    }

    long capSeconds = Math.min(maxSeconds, (long) baseSeconds * (1L << (attemptNumber - 1)));
    long jitteredSeconds = random.nextLong(capSeconds / 2, capSeconds + 1);

    long delaySeconds = jitteredSeconds;
    if (retryAfterSeconds != null && retryAfterSeconds > 0) {
      long retryAfterClamped = Math.min(retryAfterSeconds, maxSeconds);
      delaySeconds = Math.max(delaySeconds, retryAfterClamped);
    }

    Instant nextAttempt = now.plus(delaySeconds, ChronoUnit.SECONDS);
    if (nextAttempt.isAfter(now.plus(cycleDeadline))) {
      return null;
    }
    return nextAttempt;
  }

  /** Returns the base backoff interval in seconds. */
  public int baseSeconds() {
    return baseSeconds;
  }

  /** Returns the maximum backoff interval in seconds. */
  public int maxSeconds() {
    return maxSeconds;
  }

  /** Returns the cycle deadline duration. */
  public Duration cycleDeadline() {
    return cycleDeadline;
  }
}
