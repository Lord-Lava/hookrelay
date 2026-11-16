package com.hookrelay.endpoint.api;

import com.hookrelay.auth.TenantContext;
import com.hookrelay.endpoint.application.EndpointSubscriptionService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/endpoints")
public class EndpointSubscriptionController {

  private final EndpointSubscriptionService subscriptionService;

  public EndpointSubscriptionController(EndpointSubscriptionService subscriptionService) {
    this.subscriptionService = subscriptionService;
  }

  @PutMapping("/{endpointId}/subscriptions/{eventType}")
  public ResponseEntity<SubscriptionResponse> createOrUpdateSubscription(
      @PathVariable UUID endpointId, @PathVariable String eventType) {
    UUID projectId = TenantContext.require().projectId();
    EndpointSubscriptionService.SubscriptionResult result =
        subscriptionService.createOrUpdateSubscription(projectId, endpointId, eventType);
    if (result.created()) {
      return ResponseEntity.status(201).body(result.response());
    }
    return ResponseEntity.ok(result.response());
  }

  @DeleteMapping("/{endpointId}/subscriptions/{eventType}")
  public ResponseEntity<Void> deleteSubscription(
      @PathVariable UUID endpointId, @PathVariable String eventType) {
    UUID projectId = TenantContext.require().projectId();
    subscriptionService.deleteSubscription(projectId, endpointId, eventType);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{endpointId}/subscriptions")
  public ResponseEntity<SubscriptionListResponse> listSubscriptions(
      @PathVariable UUID endpointId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (page < 0) {
      throw new IllegalArgumentException("page must be greater than or equal to 0");
    }
    if (size <= 0 || size > 100) {
      throw new IllegalArgumentException("size must be between 1 and 100");
    }
    UUID projectId = TenantContext.require().projectId();
    List<com.hookrelay.endpoint.domain.EndpointSubscription> subscriptions =
        subscriptionService.listSubscriptions(projectId, endpointId, page, size);
    int total = subscriptionService.countSubscriptions(projectId, endpointId);
    List<SubscriptionResponse> responses =
        subscriptions.stream()
            .map(
                s -> new SubscriptionResponse(s.id(), s.endpointId(), s.eventType(), s.createdAt()))
            .toList();
    return ResponseEntity.ok(new SubscriptionListResponse(responses, page, size, total));
  }
}
