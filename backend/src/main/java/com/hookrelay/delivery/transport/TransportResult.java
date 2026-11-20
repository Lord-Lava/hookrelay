package com.hookrelay.delivery.transport;

import java.time.Instant;

/**
 * Result of a webhook transport send operation.
 *
 * <p>Response body is intentionally bounded; full bodies are never retained. The body field
 * contains at most {@link WebhookTransportProperties#maxResponseBodyBytes()} bytes and is only
 * populated for diagnostic purposes on error responses.
 */
public record TransportResult(int statusCode, String body, Instant sentAt) {}
