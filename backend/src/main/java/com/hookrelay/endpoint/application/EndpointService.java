package com.hookrelay.endpoint.application;

import com.hookrelay.endpoint.domain.WebhookEndpoint;
import com.hookrelay.endpoint.repository.WebhookEndpointRepository;
import com.hookrelay.security.SigningSecretService;
import com.hookrelay.security.ssrf.SsrfUrlValidator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class EndpointService {

  private final WebhookEndpointRepository endpointRepository;
  private final SigningSecretService signingSecretService;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final SsrfUrlValidator ssrfUrlValidator;

  public EndpointService(
      WebhookEndpointRepository endpointRepository,
      SigningSecretService signingSecretService,
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      SsrfUrlValidator ssrfUrlValidator) {
    this.endpointRepository = endpointRepository;
    this.signingSecretService = signingSecretService;
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.ssrfUrlValidator = ssrfUrlValidator;
  }

  /**
   * Creates an endpoint and generates a new signing secret. The plaintext secret is returned once
   * alongside the persisted endpoint; it is never stored in plaintext.
   */
  @Transactional
  public CreatedEndpointResult createEndpoint(UUID projectId, String url, String description) {
    ssrfUrlValidator.validate(url);

    List<WebhookEndpoint> existing = endpointRepository.findByProject(projectId);
    if (existing.stream().anyMatch(e -> e.url().equalsIgnoreCase(url))) {
      throw new EndpointAlreadyExistsException(
          "Endpoint URL already exists for this project: " + url);
    }

    if (!signingSecretService.isConfigured()) {
      // No encryption key configured - store empty ciphertext sentinel (backward compatible)
      // Do not expose this value through any API response.
      WebhookEndpoint endpoint =
          new WebhookEndpoint(
              null, projectId, url, description, "", null, null, true, null, null, null);
      try {
        return new CreatedEndpointResult(endpointRepository.save(endpoint), null);
      } catch (DataIntegrityViolationException exception) {
        throw new EndpointAlreadyExistsException(
            "Endpoint URL already exists for this project: " + url, exception);
      }
    }

    String plaintextSecret = signingSecretService.generateSecret();
    SigningSecretService.EncryptedSecret encrypted = signingSecretService.encrypt(plaintextSecret);

    WebhookEndpoint endpoint =
        new WebhookEndpoint(
            null,
            projectId,
            url,
            description,
            encrypted.ciphertext(),
            encrypted.nonce(),
            encrypted.keyVersion(),
            true,
            null,
            null,
            null);
    try {
      WebhookEndpoint saved = endpointRepository.save(endpoint);
      return new CreatedEndpointResult(saved, plaintextSecret);
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
      ssrfUrlValidator.validate(resolvedUrl);

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
            existing.nonce(),
            existing.keyVersion(),
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
            existing.nonce(),
            existing.keyVersion(),
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
            existing.nonce(),
            existing.keyVersion(),
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
   * Decrypts and returns the plaintext signing secret for an endpoint. Used internally by delivery
   * signing; never exposed through API responses.
   */
  public String getSigningSecret(UUID id, UUID projectId) {
    if (signingSecretService == null) {
      throw new IllegalStateException("Signing secret encryption is not configured");
    }
    WebhookEndpoint endpoint = getEndpoint(id, projectId);
    if (endpoint.signingSecretCiphertext() == null
        || endpoint.signingSecretCiphertext().isBlank()
        || endpoint.nonce() == null
        || endpoint.keyVersion() == null) {
      throw new IllegalStateException("Endpoint has no signing secret configured: " + id);
    }
    return signingSecretService.decrypt(
        endpoint.signingSecretCiphertext(), endpoint.nonce(), endpoint.keyVersion());
  }

  /**
   * Rotates the signing secret for an endpoint atomically. Generates a fresh secret, encrypts it,
   * and persists the new ciphertext/nonce/key version in the same transaction as an audit event.
   * The plaintext secret is returned once; subsequent reads never expose it.
   */
  @Transactional
  public RotateSecretResult rotateSigningSecret(UUID id, UUID projectId) {
    if (!signingSecretService.isConfigured()) {
      throw new IllegalStateException("Signing secret encryption is not configured");
    }

    WebhookEndpoint existing = getEndpoint(id, projectId);

    String plaintextSecret = signingSecretService.generateSecret();
    SigningSecretService.EncryptedSecret encrypted = signingSecretService.encrypt(plaintextSecret);

    WebhookEndpoint updated =
        new WebhookEndpoint(
            existing.id(),
            existing.projectId(),
            existing.url(),
            existing.description(),
            encrypted.ciphertext(),
            encrypted.nonce(),
            encrypted.keyVersion(),
            existing.enabled(),
            existing.createdAt(),
            Instant.now(),
            existing.deletedAt());
    try {
      endpointRepository.save(updated);
    } catch (DataIntegrityViolationException exception) {
      throw new EndpointAlreadyExistsException("Endpoint rotation conflict for: " + id, exception);
    }

    // Record audit event without storing any secret material.
    recordSecretRotationAudit(projectId, id);

    return new RotateSecretResult(updated, plaintextSecret);
  }

  private void recordSecretRotationAudit(UUID projectId, UUID endpointId) {
    try {
      String payload =
          objectMapper.writeValueAsString(
              new SecretRotationAuditEvent(endpointId, "SECRET_ROTATED", Instant.now()));
      jdbcTemplate.update(
          """
          INSERT INTO outbox_messages (id, project_id, aggregate_id, message_type, payload)
          VALUES (?, ?, ?, 'SECRET_ROTATED', ?::jsonb)
          """,
          UUID.randomUUID(),
          projectId,
          endpointId,
          payload);
    } catch (Exception exception) {
      throw new IllegalStateException("Failed to record secret rotation audit event", exception);
    }
  }

  /** Holds the result of creating an endpoint, including the plaintext signing secret. */
  public record CreatedEndpointResult(WebhookEndpoint endpoint, String signingSecret) {}

  /** Holds the result of rotating a signing secret, including the new plaintext secret. */
  public record RotateSecretResult(WebhookEndpoint endpoint, String signingSecret) {}

  /** Audit event payload for secret rotation - contains no secret material. */
  private record SecretRotationAuditEvent(UUID endpointId, String eventType, Instant timestamp) {}
}
