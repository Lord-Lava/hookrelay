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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
    com.hookrelay.delivery.domain.DeliveryAttempt attempt =
        transactions
            .execute(tx -> deliveryStore.startAttempt(projectId, deliveryId, leaseToken))
            .orElse(null);

    if (attempt == null) {
      return null;
    }

    Delivery delivery = deliveryStore.find(projectId, deliveryId).orElseThrow();

    TransportResult result;
    try {
      result = sendDelivery(delivery);
    } catch (Exception e) {
      result = TransportResult.failure(e);
    }

    Instant retryAt = result.retryAt();
    if (retryAt == null && result.outcome() == AttemptOutcome.RETRYABLE_FAILURE) {
      retryAt = nowPlusRetry(delivery);
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
              finalResult.durationMs(),
              finalResult.errorCode(),
              finalRetryAt);
      return finalized ? deliveryStore.find(projectId, deliveryId).orElse(null).status() : null;
    });
  }

  private TransportResult sendDelivery(Delivery delivery) throws TransportException {
    Instant now = Instant.now();
    long timestampSeconds = now.getEpochSecond();

    FanoutSnapshot snapshot =
        fanoutStore.find(delivery.projectId(), delivery.eventId()).orElseThrow();
    byte[] bodyBytes = snapshot.body();

    String secretPlaintext =
        endpointService.getSigningSecret(delivery.endpointId(), delivery.projectId());
    String signature = signer.sign(delivery.id(), timestampSeconds, bodyBytes, secretPlaintext);

    Map<String, String> headers =
        Map.of(
            "webhook-id", delivery.id().toString(),
            "webhook-timestamp", Long.toString(timestampSeconds),
            "webhook-signature", signature);

    Instant sendStart = Instant.now();
    TransportResult result = transport.send(delivery.destinationUrl(), bodyBytes, headers);
    long durationMs = Duration.between(sendStart, Instant.now()).toMillis();

    return new TransportResult(
        result.statusCode(),
        result.body(),
        result.sentAt(),
        result.outcome(),
        durationMs,
        result.errorCode(),
        result.retryAt());
  }

  private Instant nowPlusRetry(Delivery delivery) {
    return retryScheduler.nextAttemptAt(delivery.attemptCount() + 1, null, Instant.now());
  }
}
