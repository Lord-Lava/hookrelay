package com.hookrelay.endpoint.api;

import com.hookrelay.auth.TenantContext;
import com.hookrelay.endpoint.application.EndpointService;
import com.hookrelay.endpoint.domain.WebhookEndpoint;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/endpoints")
public class EndpointController {

  private final EndpointService endpointService;

  public EndpointController(EndpointService endpointService) {
    this.endpointService = endpointService;
  }

  @PostMapping
  public ResponseEntity<CreatedEndpointResponse> createEndpoint(
      @RequestBody @Valid EndpointCreateRequest request) {
    UUID projectId = TenantContext.require().projectId();
    EndpointService.CreatedEndpointResult result =
        endpointService.createEndpoint(projectId, request.url(), request.description());
    return ResponseEntity.status(201).body(toCreatedResponse(result));
  }

  @GetMapping
  public ResponseEntity<EndpointListResponse> listEndpoints(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    if (page < 0) {
      throw new IllegalArgumentException("page must be greater than or equal to 0");
    }
    if (size <= 0 || size > 100) {
      throw new IllegalArgumentException("size must be between 1 and 100");
    }
    UUID projectId = TenantContext.require().projectId();
    List<WebhookEndpoint> endpoints = endpointService.listEndpoints(projectId, page, size);
    List<EndpointResponse> responses = endpoints.stream().map(this::toResponse).toList();
    int total = endpointService.countEndpoints(projectId);
    return ResponseEntity.ok(new EndpointListResponse(responses, page, size, total));
  }

  @GetMapping("/{endpointId}")
  public ResponseEntity<EndpointResponse> getEndpoint(@PathVariable UUID endpointId) {
    UUID projectId = TenantContext.require().projectId();
    WebhookEndpoint endpoint = endpointService.getEndpoint(endpointId, projectId);
    return ResponseEntity.ok(toResponse(endpoint));
  }

  @PatchMapping("/{endpointId}")
  public ResponseEntity<EndpointResponse> updateEndpoint(
      @PathVariable UUID endpointId, @RequestBody @Valid EndpointUpdateRequest request) {
    UUID projectId = TenantContext.require().projectId();
    WebhookEndpoint endpoint =
        endpointService.updateEndpoint(
            endpointId, projectId, request.url(), request.description(), request.enabled());
    return ResponseEntity.ok(toResponse(endpoint));
  }

  @PostMapping("/{endpointId}/enable")
  public ResponseEntity<Void> enableEndpoint(@PathVariable UUID endpointId) {
    UUID projectId = TenantContext.require().projectId();
    endpointService.enableEndpoint(endpointId, projectId);
    return ResponseEntity.ok().build();
  }

  @PostMapping("/{endpointId}/disable")
  public ResponseEntity<Void> disableEndpoint(@PathVariable UUID endpointId) {
    UUID projectId = TenantContext.require().projectId();
    endpointService.disableEndpoint(endpointId, projectId);
    return ResponseEntity.ok().build();
  }

  @DeleteMapping("/{endpointId}")
  public ResponseEntity<Void> deleteEndpoint(@PathVariable UUID endpointId) {
    UUID projectId = TenantContext.require().projectId();
    endpointService.deleteEndpoint(endpointId, projectId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{endpointId}/rotate-secret")
  public ResponseEntity<RotateSecretResponse> rotateSecret(@PathVariable UUID endpointId) {
    UUID projectId = TenantContext.require().projectId();
    EndpointService.RotateSecretResult result =
        endpointService.rotateSigningSecret(endpointId, projectId);
    WebhookEndpoint endpoint = result.endpoint();
    return ResponseEntity.ok(
        new RotateSecretResponse(
            endpoint.id(),
            endpoint.projectId(),
            endpoint.url(),
            result.signingSecret(),
            endpoint.enabled(),
            endpoint.updatedAt()));
  }

  private CreatedEndpointResponse toCreatedResponse(EndpointService.CreatedEndpointResult result) {
    WebhookEndpoint endpoint = result.endpoint();
    return new CreatedEndpointResponse(
        endpoint.id(),
        endpoint.projectId(),
        endpoint.url(),
        endpoint.description(),
        result.signingSecret(),
        endpoint.enabled(),
        endpoint.createdAt(),
        endpoint.updatedAt());
  }

  private EndpointResponse toResponse(WebhookEndpoint endpoint) {
    return new EndpointResponse(
        endpoint.id(),
        endpoint.projectId(),
        endpoint.url(),
        endpoint.description(),
        endpoint.enabled(),
        endpoint.createdAt(),
        endpoint.updatedAt());
  }
}
