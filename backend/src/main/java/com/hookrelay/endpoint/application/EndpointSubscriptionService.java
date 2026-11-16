package com.hookrelay.endpoint.application;

import com.hookrelay.endpoint.api.SubscriptionResponse;
import com.hookrelay.endpoint.domain.EndpointSubscription;
import com.hookrelay.endpoint.domain.WebhookEndpoint;
import com.hookrelay.endpoint.repository.EndpointSubscriptionRepository;
import com.hookrelay.endpoint.repository.WebhookEndpointRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EndpointSubscriptionService {

  private static final String EVENT_TYPE_PATTERN = "[a-z0-9]+([._-][a-z0-9]+)*";
  private static final int MAX_EVENT_TYPE_LENGTH = 128;

  private final WebhookEndpointRepository endpointRepository;
  private final EndpointSubscriptionRepository subscriptionRepository;

  public EndpointSubscriptionService(
      WebhookEndpointRepository endpointRepository,
      EndpointSubscriptionRepository subscriptionRepository) {
    this.endpointRepository = endpointRepository;
    this.subscriptionRepository = subscriptionRepository;
  }

  /**
   * Creates or idempotently returns an existing subscription for the given endpoint and event type.
   * All operations are scoped to the authenticated project.
   *
   * @return the subscription and a flag indicating whether it was newly created
   */
  @Transactional
  public SubscriptionResult createOrUpdateSubscription(
      UUID projectId, UUID endpointId, String eventType) {
    validateEventType(eventType);

    // Verify the endpoint exists and belongs to the authenticated project (tenant scope).
    // Returns 404 for both nonexistent endpoints and cross-project access to avoid leaking
    // information about other projects' endpoints.
    WebhookEndpoint endpoint =
        endpointRepository
            .findByIdAndProject(endpointId, projectId)
            .orElseThrow(() -> new EndpointNotFoundException("Endpoint not found: " + endpointId));

    // Idempotent: if subscription already exists, return it without creating a duplicate.
    EndpointSubscription existing =
        subscriptionRepository
            .findByEndpointIdAndEventTypeAndProject(endpointId, eventType, projectId)
            .orElse(null);
    if (existing != null) {
      return new SubscriptionResult(toResponse(existing), false);
    }

    EndpointSubscription subscription =
        new EndpointSubscription(null, endpointId, eventType, null, null);
    EndpointSubscription saved = subscriptionRepository.save(subscription);
    return new SubscriptionResult(toResponse(saved), true);
  }

  @Transactional
  public void deleteSubscription(UUID projectId, UUID endpointId, String eventType) {
    validateEventType(eventType);

    // Verify the endpoint exists and belongs to the authenticated project.
    endpointRepository
        .findByIdAndProject(endpointId, projectId)
        .orElseThrow(() -> new EndpointNotFoundException("Endpoint not found: " + endpointId));

    // Soft-delete the subscription. Returns 0 rows if no matching active subscription exists,
    // which means either the subscription doesn't exist or it was already soft-deleted.
    int deleted =
        subscriptionRepository.softDeleteByEndpointIdAndEventTypeAndProject(
            endpointId, eventType, projectId);
    if (deleted == 0) {
      throw new SubscriptionNotFoundException(
          "Subscription not found for event type: " + eventType);
    }
  }

  public List<EndpointSubscription> listSubscriptions(
      UUID projectId, UUID endpointId, int page, int size) {
    // Verify the endpoint exists and belongs to the authenticated project.
    endpointRepository
        .findByIdAndProject(endpointId, projectId)
        .orElseThrow(() -> new EndpointNotFoundException("Endpoint not found: " + endpointId));

    int offset = page * size;
    return subscriptionRepository.findByEndpointIdAndProjectPaginated(
        endpointId, projectId, offset, size);
  }

  public int countSubscriptions(UUID projectId, UUID endpointId) {
    return subscriptionRepository.countByEndpointIdAndProject(endpointId, projectId);
  }

  private void validateEventType(String eventType) {
    if (eventType == null || eventType.isBlank()) {
      throw new IllegalArgumentException("eventType must not be blank");
    }
    if (eventType.length() > MAX_EVENT_TYPE_LENGTH) {
      throw new IllegalArgumentException(
          "eventType must not exceed " + MAX_EVENT_TYPE_LENGTH + " characters");
    }
    if (!eventType.matches(EVENT_TYPE_PATTERN)) {
      throw new IllegalArgumentException(
          "eventType must be lowercase and contain only letters, numbers, dots, underscores or hyphens");
    }
  }

  private SubscriptionResponse toResponse(EndpointSubscription subscription) {
    return new SubscriptionResponse(
        subscription.id(),
        subscription.endpointId(),
        subscription.eventType(),
        subscription.createdAt());
  }

  /** Holds the result of an idempotent create-or-update subscription operation. */
  public record SubscriptionResult(SubscriptionResponse response, boolean created) {}
}
