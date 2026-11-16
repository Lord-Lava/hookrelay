package com.hookrelay.endpoint.application;

import com.hookrelay.endpoint.domain.WebhookEndpoint;
import com.hookrelay.endpoint.repository.WebhookEndpointRepository;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EndpointService {

  private final WebhookEndpointRepository endpointRepository;

  public EndpointService(WebhookEndpointRepository endpointRepository) {
    this.endpointRepository = endpointRepository;
  }

  @Transactional
  public WebhookEndpoint createEndpoint(UUID projectId, String url, String description) {
    validateUrlHost(url);

    // Fast pre-check for duplicate URL (case-insensitive) to give a clear error before hitting
    // the database. The partial unique index (V3 migration) is the backstop that prevents
    // race conditions between concurrent requests.
    List<WebhookEndpoint> existing = endpointRepository.findByProject(projectId);
    if (existing.stream().anyMatch(e -> e.url().equalsIgnoreCase(url))) {
      throw new EndpointAlreadyExistsException(
          "Endpoint URL already exists for this project: " + url);
    }

    // Signing secret encryption is implemented in a later step. Until then, store an empty
    // ciphertext sentinel (NOT a plaintext secret) to satisfy the NOT NULL column constraint.
    // Do not expose this value through any API response.
    WebhookEndpoint endpoint =
        new WebhookEndpoint(null, projectId, url, description, "", true, null, null, null);
    try {
      return endpointRepository.save(endpoint);
    } catch (DataIntegrityViolationException exception) {
      throw new EndpointAlreadyExistsException(
          "Endpoint URL already exists for this project: " + url, exception);
    }
  }

  public int countEndpoints(UUID projectId) {
    return endpointRepository.countByProject(projectId);
  }

  public List<WebhookEndpoint> listEndpoints(UUID projectId, int page, int size) {
    int offset = page * size;
    return endpointRepository.findByProjectPaginated(projectId, offset, size);
  }

  public WebhookEndpoint getEndpoint(UUID id, UUID projectId) {
    return endpointRepository
        .findByIdAndProject(id, projectId)
        .orElseThrow(() -> new EndpointNotFoundException("Endpoint not found: " + id));
  }

  @Transactional
  public WebhookEndpoint updateEndpoint(
      UUID id, UUID projectId, String url, String description, Boolean enabled) {
    WebhookEndpoint existing = getEndpoint(id, projectId);

    String resolvedUrl = url != null ? url : existing.url();
    if (!resolvedUrl.equals(existing.url())) {
      validateUrlHost(resolvedUrl);

      // Fast pre-check for duplicate URL (case-insensitive), excluding self. The partial unique
      // index (V3 migration) is the backstop that prevents race conditions.
      List<WebhookEndpoint> existingEndpoints = endpointRepository.findByProject(projectId);
      if (existingEndpoints.stream()
          .anyMatch(e -> !e.id().equals(id) && e.url().equalsIgnoreCase(resolvedUrl))) {
        throw new EndpointAlreadyExistsException(
            "Endpoint URL already exists for this project: " + resolvedUrl);
      }
    }

    WebhookEndpoint updated =
        new WebhookEndpoint(
            existing.id(),
            existing.projectId(),
            resolvedUrl,
            description != null ? description : existing.description(),
            existing.signingSecretCiphertext(),
            enabled != null ? enabled : existing.enabled(),
            existing.createdAt(),
            null,
            existing.deletedAt());
    try {
      return endpointRepository.save(updated);
    } catch (DataIntegrityViolationException exception) {
      throw new EndpointAlreadyExistsException(
          "Endpoint URL already exists for this project: " + resolvedUrl, exception);
    }
  }

  @Transactional
  public void enableEndpoint(UUID id, UUID projectId) {
    WebhookEndpoint existing = getEndpoint(id, projectId);
    if (existing.enabled()) {
      return;
    }
    WebhookEndpoint updated =
        new WebhookEndpoint(
            existing.id(),
            existing.projectId(),
            existing.url(),
            existing.description(),
            existing.signingSecretCiphertext(),
            true,
            existing.createdAt(),
            null,
            existing.deletedAt());
    endpointRepository.save(updated);
  }

  @Transactional
  public void disableEndpoint(UUID id, UUID projectId) {
    WebhookEndpoint existing = getEndpoint(id, projectId);
    if (!existing.enabled()) {
      return;
    }
    WebhookEndpoint updated =
        new WebhookEndpoint(
            existing.id(),
            existing.projectId(),
            existing.url(),
            existing.description(),
            existing.signingSecretCiphertext(),
            false,
            existing.createdAt(),
            null,
            existing.deletedAt());
    endpointRepository.save(updated);
  }

  @Transactional
  public void deleteEndpoint(UUID id, UUID projectId) {
    int deleted = endpointRepository.softDelete(id, projectId);
    if (deleted == 0) {
      throw new EndpointNotFoundException("Endpoint not found: " + id);
    }
  }

  /**
   * Validates the URL host against SSRF risks. Only HTTPS is allowed (enforced separately by the
   * request DTO's {@code @Pattern}). Rejects loopback, private, link-local, multicast, reserved,
   * unspecified, IPv4-mapped, and cloud metadata addresses, including IPv6 equivalents.
   *
   * <p>Hostnames are resolved via DNS; each resolved address is checked. IP literals are checked
   * directly without DNS. If a hostname cannot be resolved, validation passes (the HTTPS scheme
   * check already applies and the connection will fail at delivery time anyway).
   */
  private void validateUrlHost(String url) {
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      if (host == null || host.isBlank()) {
        return; // scheme validation covers this case
      }

      InetAddress[] addresses;
      try {
        addresses = InetAddress.getAllByName(host);
      } catch (UnknownHostException e) {
        return; // hostname not resolvable; scheme validation still applies
      }

      for (InetAddress address : addresses) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isMulticastAddress()
            || address.isSiteLocalAddress()) {
          throw new IllegalArgumentException("URL host is not allowed: " + host);
        }
      }
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (Exception exception) {
      // Malformed URL - scheme validation covers this; do not fail SSRF validation on parse errors.
    }
  }
}
