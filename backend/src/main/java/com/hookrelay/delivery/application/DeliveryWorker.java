package com.hookrelay.delivery.application;

import com.hookrelay.delivery.domain.AttemptOutcome;
import com.hookrelay.delivery.domain.Delivery;
import com.hookrelay.delivery.domain.DeliveryStatus;
import com.hookrelay.delivery.domain.FanoutSnapshot;
import com.hookrelay.delivery.repository.DeliveryStore;
import com.hookrelay.delivery.repository.FanoutStore;
import com.hookrelay.delivery.signing.WebhookSigner;
import com.hookrelay.delivery.transport.TransportException;
import com.hookrelay.delivery.transport.TransportResult;
import com.hookrelay.delivery.transport.WebhookTransport;
import com.hookrelay.endpoint.application.EndpointService;
import com.hookrelay.security.ssrf.SsrfValidationException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes a single claimed delivery: start attempt, sign body, send via HTTPS transport, finalize
 * attempt result. All work is performed within short database transactions; no network I/O is held
 * inside a transaction.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "hookrelay.delivery.dispatcher.enabled", havingValue = "true")
public class DeliveryWorker {

  private final DeliveryStore deliveryStore;
  private final FanoutStore fanoutStore;
  private final WebhookTransport transport;
  private final WebhookSigner signer;
  private final EndpointService endpointService;
  private final RetryScheduler retryScheduler;
  private final TransactionTemplate transactions;

  public DeliveryWorker(
      DeliveryStore deliveryStore,
      FanoutStore fanoutStore,
      WebhookTransport transport,
      WebhookSigner signer,
      EndpointService endpointService,
      RetryScheduler retryScheduler,
      PlatformTransactionManager transactionManager) {
    this.deliveryStore = deliveryStore;
    this.fanoutStore = fanoutStore;
    this.transport = transport;
    this.signer = signer;
    this.endpointService = endpointService;
    this.retryScheduler = retryScheduler;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.transactions.setTimeout(10);
  }

  /**
   * Processes one claimed delivery. Returns the delivery's current status after processing.
   *
   * <p>Steps: (1) start attempt, (2) fetch frozen body + signing secret, (3) sign, (4) send via
   * HTTPS transport, (5) finalize attempt result. Each step commits independently; network I/O is
   * never inside a database transaction.
   */
  public DeliveryStatus process(UUID projectId, UUID deliveryId, UUID leaseToken) {
    // Preflight: load delivery, fanout snapshot, and signing secret before starting attempt.
    // This ensures unavailable preparation does not consume an attempt.
    Delivery delivery = deliveryStore.find(projectId, deliveryId).orElseThrow();
    FanoutSnapshot snapshot =
        fanoutStore.find(delivery.projectId(), delivery.eventId()).orElseThrow();
    byte[] bodyBytes = snapshot.body();

    String secretPlaintext =
        endpointService.getSigningSecret(delivery.endpointId(), delivery.projectId());
    Instant now = Instant.now();
    long timestampSeconds = now.getEpochSecond();
    String signature = signer.sign(delivery.id(), timestampSeconds, bodyBytes, secretPlaintext);

    Map<String, String> headers =
        Map.of(
            "webhook-id", delivery.id().toString(),
            "webhook-timestamp", Long.toString(timestampSeconds),
            "webhook-signature", signature);

    com.hookrelay.delivery.domain.DeliveryAttempt attempt =
        transactions
            .execute(tx -> deliveryStore.startAttempt(projectId, deliveryId, leaseToken))
            .orElse(null);

    if (attempt == null) {
      return null;
    }

    TransportResult result;
    final Instant transportStartedAt = Instant.now();
    try {
      result = transport.send(delivery.destinationUrl(), bodyBytes, headers);
    } catch (Exception e) {
      long durationMs = Duration.between(transportStartedAt, Instant.now()).toMillis();
      result = classifyTransportFailure(e, transportStartedAt, durationMs);
    }

    Instant retryAt = result.retryAt();
    Instant now2 = Instant.now();
    if (retryAt != null && result.outcome() == AttemptOutcome.RETRYABLE_FAILURE) {
      long delaySeconds = Math.max(0L, Duration.between(now2, retryAt).getSeconds());
      int retryAfterHint = (int) Math.min(delaySeconds, Integer.MAX_VALUE);
      retryAt = retryScheduler.nextAttemptAt(attempt.attemptNumber(), retryAfterHint, now2);
    } else if (result.outcome() == AttemptOutcome.RETRYABLE_FAILURE) {
      retryAt = retryScheduler.nextAttemptAt(attempt.attemptNumber(), null, now2);
    }
    final TransportResult finalResult = result;
    final Instant finalRetryAt = retryAt;

    return transactions.execute(tx -> {
      boolean finalized =
          deliveryStore.finishAttempt(
              projectId,
              deliveryId,
              leaseToken,
              attempt.id(),
              finalResult.outcome(),
              finalResult.statusCode() == 0 ? null : finalResult.statusCode(),
              finalResult.durationMs() == null ? 0L : finalResult.durationMs(),
              finalResult.errorCode(),
              finalRetryAt);
      return finalized ? deliveryStore.find(projectId, deliveryId).orElse(null).status() : null;
    });
  }

  /**
   * Classifies a transport-level failure into a retryable or permanent outcome. SSRF policy
   * violations, DNS resolution failures and TLS failures are permanent security/configuration
   * failures that must not be retried; only transient I/O/connection failures are retryable.
   *
   * <p>The permanence decision is made once at the transport boundary and carried on {@link
   * TransportException}; this method reads it rather than re-deriving it by walking the cause
   * chain. A cause-walk fallback handles exceptions not produced at the transport boundary (for
   * example, test-constructed failures).
   */
  private static TransportResult classifyTransportFailure(
      Exception e, Instant startedAt, long durationMs) {
    AttemptOutcome outcome = null;
    String errorCode = null;
    if (e instanceof TransportException te && te.failureCategory() != null) {
      outcome =
          te.isPermanent() ? AttemptOutcome.PERMANENT_FAILURE : AttemptOutcome.RETRYABLE_FAILURE;
      errorCode = te.failureCategory();
    } else {
      // Fallback: exceptions not produced at the transport boundary.
      Throwable cause = e;
      while (cause != null) {
        if (cause instanceof SsrfValidationException) {
          outcome = AttemptOutcome.PERMANENT_FAILURE;
          errorCode = "SSRF_REJECTED";
          break;
        }
        if (cause instanceof SSLException) {
          outcome = AttemptOutcome.PERMANENT_FAILURE;
          errorCode = "TLS_FAILURE";
          break;
        }
        if (cause instanceof UnknownHostException) {
          outcome = AttemptOutcome.PERMANENT_FAILURE;
          errorCode = "DNS_FAILURE";
          break;
        }
        cause = cause.getCause();
      }
      if (outcome == null) {
        outcome = AttemptOutcome.RETRYABLE_FAILURE;
        errorCode = "TRANSPORT_FAILURE";
      }
    }
    return new TransportResult(0, null, startedAt, outcome, durationMs, errorCode, null);
  }
}
