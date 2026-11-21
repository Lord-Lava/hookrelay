package com.hookrelay.delivery.application;

import com.hookrelay.delivery.domain.Delivery;
import com.hookrelay.delivery.domain.DeliveryStatus;
import com.hookrelay.delivery.repository.DeliveryStore;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls PostgreSQL for due deliveries and dispatches them to workers.
 *
 * <p>Claims deliveries using {@code FOR UPDATE SKIP LOCKED} to avoid concurrent claim conflicts.
 * Respects per-project and per-endpoint concurrency and rate limits via {@link
 * DispatchControlService}. Processing is delegated to {@link DeliveryWorker}.
 */
@Component
@ConditionalOnProperty(name = "hookrelay.delivery.dispatcher.enabled", havingValue = "true")
public class DeliveryDispatcher {

  private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);

  private final DeliveryStore deliveryStore;
  private final DeliveryWorker deliveryWorker;
  private final DispatchControlService dispatchControl;
  private final DeliveryDispatcherProperties properties;

  public DeliveryDispatcher(
      DeliveryStore deliveryStore,
      DeliveryWorker deliveryWorker,
      DispatchControlService dispatchControl,
      DeliveryDispatcherProperties properties) {
    this.deliveryStore = deliveryStore;
    this.deliveryWorker = deliveryWorker;
    this.dispatchControl = dispatchControl;
    this.properties = properties;
  }

  /**
   * Polls for due deliveries and dispatches them. Called on a fixed schedule.
   *
   * <p>Claims a bounded batch of due deliveries, processes each one, and releases capacity. Failed
   * claims or processing errors are logged but do not stop the poll cycle.
   */
  @Scheduled(fixedDelayString = "${hookrelay.delivery.dispatcher.polling-interval:250ms}")
  public void pollAndDispatch() {
    if (!properties.enabled()) {
      return;
    }

    Instant now = Instant.now();
    List<Delivery> due = deliveryStore.listDue(now, properties.maxConcurrentClaims());

    for (Delivery delivery : due) {
      if (!dispatchControl.tryAcquire(
          delivery.projectId(),
          delivery.endpointId(),
          properties.maxConcurrentPerProject(),
          properties.maxConcurrentPerEndpoint(),
          properties.ratePerProjectPerSecond(),
          properties.ratePerEndpointPerSecond())) {
        continue;
      }

      try {
        DeliveryStatus status =
            deliveryWorker.process(delivery.projectId(), delivery.id(), delivery.leaseToken());
        if (status == null) {
          // Claim was lost to another instance; release capacity
          dispatchControl.release(delivery.projectId(), delivery.endpointId());
        }
      } catch (Exception e) {
        log.warn("Delivery {} processing failed: {}", delivery.id(), e.getMessage(), e);
        dispatchControl.release(delivery.projectId(), delivery.endpointId());
      }
    }
  }

  /** Recovers expired leases and retries them. */
  @Scheduled(fixedDelayString = "${hookrelay.delivery.dispatcher.recovery-interval:5s}")
  public void recoverExpiredLeases() {
    if (!properties.enabled()) {
      return;
    }

    Instant now = Instant.now();
    List<Delivery> expired = deliveryStore.listExpiredLeases(now, properties.maxConcurrentClaims());

    for (Delivery delivery : expired) {
      if (!dispatchControl.tryAcquire(
          delivery.projectId(),
          delivery.endpointId(),
          properties.maxConcurrentPerProject(),
          properties.maxConcurrentPerEndpoint(),
          properties.ratePerProjectPerSecond(),
          properties.ratePerEndpointPerSecond())) {
        continue;
      }

      try {
        deliveryStore.recoverExpired(
            delivery.projectId(), delivery.id(), delivery.leaseToken(), now);
        dispatchControl.release(delivery.projectId(), delivery.endpointId());
      } catch (Exception e) {
        log.warn("Lease recovery for delivery {} failed: {}", delivery.id(), e.getMessage(), e);
        dispatchControl.release(delivery.projectId(), delivery.endpointId());
      }
    }
  }
}
