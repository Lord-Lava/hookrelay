package com.hookrelay.delivery.transport;

import com.hookrelay.delivery.domain.AttemptOutcome;
import java.time.Instant;

/**
 * Result of a webhook transport send operation.
 *
 * <p>Response body is intentionally bounded; full bodies are never retained. The body field
 * contains at most {@link WebhookTransportProperties#maxResponseBodyBytes()} bytes and is only
 * populated for diagnostic purposes on error responses.
 */
public record TransportResult(
    int statusCode,
    String body,
    Instant sentAt,
    AttemptOutcome outcome,
    Long durationMs,
    String errorCode,
    Instant retryAt) {

  /** Successful delivery with HTTP 2xx. */
  public static TransportResult success(int statusCode, String body, Instant sentAt) {
    return new TransportResult(
        statusCode, body, sentAt, AttemptOutcome.SUCCEEDED, null, null, null);
  }

  /** Retryable failure: connection error, timeout, 408, 429, 5xx. */
  public static TransportResult retryable(
      int statusCode,
      String body,
      Instant sentAt,
      Long durationMs,
      String errorCode,
      Instant retryAt) {
    return new TransportResult(
        statusCode, body, sentAt, AttemptOutcome.RETRYABLE_FAILURE, durationMs, errorCode, retryAt);
  }

  /** Permanent failure: ordinary 4xx, TLS failure, etc. */
  public static TransportResult permanent(
      int statusCode, String body, Instant sentAt, Long durationMs, String errorCode) {
    return new TransportResult(
        statusCode, body, sentAt, AttemptOutcome.PERMANENT_FAILURE, durationMs, errorCode, null);
  }

  /** Unknown outcome: could not determine if delivery succeeded. */
  public static TransportResult unknown(Instant sentAt, String errorCode) {
    return new TransportResult(0, null, sentAt, AttemptOutcome.UNKNOWN, null, errorCode, null);
  }

  /** Generic failure with exception. */
  public static TransportResult failure(Exception e) {
    return new TransportResult(
        0,
        null,
        Instant.now(),
        AttemptOutcome.RETRYABLE_FAILURE,
        null,
        e.getClass().getSimpleName(),
        null);
  }
}
