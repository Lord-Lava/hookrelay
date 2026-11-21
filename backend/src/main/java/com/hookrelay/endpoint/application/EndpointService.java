package com.hookrelay.endpoint.application;

import com.hookrelay.endpoint.domain.WebhookEndpoint;
import com.hookrelay.endpoint.repository.WebhookEndpointRepository;
import com.hookrelay.security.SigningSecretService;
import com.hookrelay.security.SigningUnavailableException;
import com.hookrelay.security.ssrf.SsrfUrlValidator;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class EndpointService {

  private final WebhookEndpointRepository endpointRepository;
  private final SigningSecretService signingSecretService;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final SsrfUrlValidator ssrfUrlValidator;
  private final TransactionTemplate transactions;

  public EndpointService(
      WebhookEndpointRepository endpointRepository,
      SigningSecretService signingSecretService,
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      SsrfUrlValidator ssrfUrlValidator,
      TransactionTemplate transactions) {
    this.endpointRepository = endpointRepository;
    this.signingSecretService = signingSecretService;
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.ssrfUrlValidator = ssrfUrlValidator;
    this.transactions = transactions;
  }

  /**
   * Creates an endpoint and generates a new signing secret. The plaintext secret is returned once
   * alongside the persisted endpoint; it is never stored in plaintext.
   */
  public CreatedEndpointResult createEndpoint(UUID projectId, String url, String description) {
    ssrfUrlValidator.validate(url);
    return transactions.execute(
        status -> createValidatedEndpoint(projectId, EndpointUrls.canonical(url), description));
  }

  private CreatedEndpointResult createValidatedEndpoint(
      UUID projectId, String url, String description) {

    if (!signingSecretService.isConfigured()) throw new SigningUnavailableException();

    jdbcTemplate.queryForObject(
        "SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, projectId);
    if (endpointRepository.countByProject(projectId) >= 1000) throw new EndpointCapacityException();
    String plaintextSecret = signingSecretService.generateSecret();
    SigningSecretService.EncryptedSecret encrypted = signingSecretService.encrypt(plaintextSecret);

    WebhookEndpoint endpoint =
        WebhookEndpoint.builder()
            .id(null)
            .projectId(projectId)
            .url(url)
            .description(description)
            .signingSecretCiphertext(encrypted.ciphertext())
            .nonce(encrypted.nonce())
            .keyVersion(encrypted.keyVersion())
            .enabled(true)
            .createdAt(null)
            .updatedAt(null)
            .deletedAt(null)
            .build();
    requireNoNullBytes(description);
    try {
      WebhookEndpoint saved = endpointRepository.save(endpoint);
      return new CreatedEndpointResult(saved, plaintextSecret);
    } catch (DataIntegrityViolationException exception) {
      throw new EndpointAlreadyExistsException(
          "Endpoint URL already exists for this project", exception);
    }
  }

  public int countEndpoints(UUID projectId) {
    return endpointRepository.countByProject(projectId);
  }

  public List<WebhookEndpoint> listEndpoints(UUID projectId, int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new IllegalArgumentException("Invalid pagination");
    long offset = (long) page * size;
    return endpointRepository.findByProjectPaginated(projectId, offset, size);
  }

  public WebhookEndpoint getEndpoint(UUID id, UUID projectId) {
    return endpointRepository
        .findByIdAndProject(id, projectId)
        .orElseThrow(() -> new EndpointNotFoundException("Endpoint not found: " + id));
  }

  public WebhookEndpoint updateEndpoint(
      UUID id, UUID projectId, String url, String description, Boolean enabled) {
    getEndpoint(id, projectId);
    if (url != null) ssrfUrlValidator.validate(url);
    return transactions.execute(status ->
        updateValidatedEndpoint(
            id, projectId, url == null ? null : EndpointUrls.canonical(url), description, enabled));
  }

  private WebhookEndpoint updateValidatedEndpoint(
      UUID id, UUID projectId, String url, String description, Boolean enabled) {
    WebhookEndpoint existing = lockEndpoint(id, projectId);

    if (Boolean.TRUE.equals(enabled)) requireSecret(existing);
    String resolvedUrl = url != null ? url : existing.url();
    String resolvedDescription = description != null ? description : existing.description();
    requireNoNullBytes(resolvedDescription);
    WebhookEndpoint updated =
        WebhookEndpoint.builder()
            .id(existing.id())
            .projectId(existing.projectId())
            .url(resolvedUrl)
            .description(resolvedDescription)
            .signingSecretCiphertext(existing.signingSecretCiphertext())
            .nonce(existing.nonce())
            .keyVersion(existing.keyVersion())
            .enabled(enabled != null ? enabled : existing.enabled())
            .createdAt(existing.createdAt())
            .updatedAt(null)
            .deletedAt(existing.deletedAt())
            .build();
    try {
      return endpointRepository.save(updated);
    } catch (DataIntegrityViolationException exception) {
      throw new EndpointAlreadyExistsException(
          "Endpoint URL already exists for this project", exception);
    }
  }

  private WebhookEndpoint lockEndpoint(UUID id, UUID projectId) {
    return endpointRepository
        .findForUpdateAndProject(id, projectId)
        .orElseThrow(() -> new EndpointNotFoundException("Endpoint not found"));
  }

  @Transactional
  public void enableEndpoint(UUID id, UUID projectId) {
    WebhookEndpoint existing = lockEndpoint(id, projectId);
    requireSecret(existing);
    if (existing.enabled()) {
      return;
    }
    WebhookEndpoint updated =
        WebhookEndpoint.builder()
            .id(existing.id())
            .projectId(existing.projectId())
            .url(existing.url())
            .description(existing.description())
            .signingSecretCiphertext(existing.signingSecretCiphertext())
            .nonce(existing.nonce())
            .keyVersion(existing.keyVersion())
            .enabled(true)
            .createdAt(existing.createdAt())
            .updatedAt(null)
            .deletedAt(existing.deletedAt())
            .build();
    endpointRepository.save(updated);
  }

  @Transactional
  public void disableEndpoint(UUID id, UUID projectId) {
    WebhookEndpoint existing = lockEndpoint(id, projectId);
    if (!existing.enabled()) {
      return;
    }
    WebhookEndpoint updated =
        WebhookEndpoint.builder()
            .id(existing.id())
            .projectId(existing.projectId())
            .url(existing.url())
            .description(existing.description())
            .signingSecretCiphertext(existing.signingSecretCiphertext())
            .nonce(existing.nonce())
            .keyVersion(existing.keyVersion())
            .enabled(false)
            .createdAt(existing.createdAt())
            .updatedAt(null)
            .deletedAt(existing.deletedAt())
            .build();
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
    if (!signingSecretService.isConfigured()) {
      throw new SigningUnavailableException();
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
      throw new SigningUnavailableException();
    }

    WebhookEndpoint existing = lockEndpoint(id, projectId);

    String plaintextSecret = signingSecretService.generateSecret();
    SigningSecretService.EncryptedSecret encrypted = signingSecretService.encrypt(plaintextSecret);

    WebhookEndpoint updated =
        endpointRepository.updateSigningSecret(
            id, projectId, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion());

    // Record audit event without storing any secret material.
    recordSecretRotationAudit(projectId, id);

    return new RotateSecretResult(updated, plaintextSecret);
  }

  private void requireSecret(WebhookEndpoint endpoint) {
    if (endpoint.signingSecretCiphertext() == null
        || endpoint.signingSecretCiphertext().isBlank()
        || endpoint.nonce() == null
        || endpoint.keyVersion() == null)
      throw new IllegalArgumentException("Rotate the signing secret before enabling this endpoint");
  }

  private static void requireNoNullBytes(String value) {
    if (value != null && value.indexOf('\u0000') >= 0) {
      throw new IllegalArgumentException("Value must not contain NUL bytes");
    }
  }

  private void recordSecretRotationAudit(UUID projectId, UUID endpointId) {
    jdbcTemplate.update(
        """
        INSERT INTO endpoint_audit_events (id, project_id, endpoint_id, action)
        VALUES (?, ?, ?, 'SECRET_ROTATED')
        """,
        UUID.randomUUID(),
        projectId,
        endpointId);
  }

  /** Holds the result of creating an endpoint, including the plaintext signing secret. */
  public record CreatedEndpointResult(WebhookEndpoint endpoint, String signingSecret) {
    @Override
    public String toString() {
      return "CreatedEndpointResult[redacted]";
    }
  }

  /** Holds the result of rotating a signing secret, including the new plaintext secret. */
  public record RotateSecretResult(WebhookEndpoint endpoint, String signingSecret) {
    @Override
    public String toString() {
      return "RotateSecretResult[redacted]";
    }
  }
}
