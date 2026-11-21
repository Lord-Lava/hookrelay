package com.hookrelay.delivery.application;

import com.hookrelay.delivery.domain.Delivery;
import com.hookrelay.delivery.domain.DeliveryStatus;
import com.hookrelay.delivery.repository.DeliveryStore;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "hookrelay.delivery.dispatcher.enabled", havingValue = "true")
public class DeliveryDispatcher {

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
        var claimed =
            deliveryStore.claim(
                delivery.projectId(), delivery.id(), "dispatcher", properties.leaseDuration());
        if (claimed.isEmpty()) {
          // Claim lost to another instance
          dispatchControl.release(delivery.projectId(), delivery.endpointId());
          continue;
        }

        try {
          deliveryWorker.process(delivery.projectId(), delivery.id(), claimed.get().leaseToken());
        } catch (Exception e) {
          log.warn("Delivery {} processing failed: {}", delivery.id(), e.getMessage(), e);
        } finally {
          dispatchControl.release(delivery.projectId(), delivery.endpointId());
        }
      } catch (Exception e) {
        log.warn("Delivery {} claim failed: {}", delivery.id(), e.getMessage(), e);
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
            delivery.projectId(), delivery.id(), delivery.leaseToken(), Instant.now());
        dispatchControl.release(delivery.projectId(), delivery.endpointId());
      } catch (Exception e) {
        log.warn("Lease recovery for delivery {} failed: {}", delivery.id(), e.getMessage(), e);
        dispatchControl.release(delivery.projectId(), delivery.endpointId());
      }
    }
  }

  /** Expires queued deliveries whose cycle deadline has passed. */
  @Scheduled(fixedDelayString = "${hookrelay.delivery.dispatcher.recovery-interval:5s}")
  public void expireHorizon() {
    if (!properties.enabled()) {
      return;
    }

    Instant now = Instant.now();
    List<Delivery> expired = deliveryStore.listExpiredLeases(now, properties.maxConcurrentClaims());

    for (Delivery delivery : expired) {
      if (delivery.status() != DeliveryStatus.PENDING
          && delivery.status() != DeliveryStatus.RETRY_SCHEDULED) {
        continue;
      }

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
        deliveryStore.expireHorizon(delivery.projectId(), delivery.id());
        dispatchControl.release(delivery.projectId(), delivery.endpointId());
      } catch (Exception e) {
        log.warn("Expiration for delivery {} failed: {}", delivery.id(), e.getMessage(), e);
        dispatchControl.release(delivery.projectId(), delivery.endpointId());
      }
    }
  }
}
