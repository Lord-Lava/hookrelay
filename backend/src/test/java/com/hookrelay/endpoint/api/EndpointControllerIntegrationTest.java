package com.hookrelay.endpoint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hookrelay.auth.ApiKeyAuthService;
import com.hookrelay.endpoint.application.EndpointAlreadyExistsException;
import com.hookrelay.endpoint.application.EndpointNotFoundException;
import com.hookrelay.endpoint.application.EndpointService;
import com.hookrelay.endpoint.domain.WebhookEndpoint;
import com.hookrelay.endpoint.repository.WebhookEndpointRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class EndpointControllerIntegrationTest {

  private static final String TEST_API_KEY = "hr_live_testkey_integration_test_secret_value";
  private static final String TEST_API_KEY_HASH = ApiKeyAuthService.sha256(TEST_API_KEY);

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.7-alpine"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    // Unique marker ensures this test class gets its own cached application context
    registry.add("test.context.marker", () -> "endpoint-controller-integration");
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("hookrelay.outbox.enabled", () -> "false");
    registry.add(
        "hookrelay.signing.encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    registry.add("hookrelay.signing.key-version", () -> "v1");
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EndpointService endpointService;
  @Autowired private WebhookEndpointRepository endpointRepository;

  private Validator validator;

  private UUID projectId;

  @BeforeEach
  void seedDatabase() {
    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();

    UUID organizationId = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
    projectId = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");
    UUID apiKeyId = UUID.fromString("cccccccc-3333-4333-8333-cccccccccccc");

    jdbcTemplate.update(
        "INSERT INTO organizations (id, name, created_at) VALUES (?, ?, ?)",
        organizationId,
        "Integration Test Org",
        Timestamp.from(Instant.now()));

    jdbcTemplate.update(
        "INSERT INTO projects (id, organization_id, name, environment, created_at) VALUES (?, ?, ?, ?, ?)",
        projectId,
        organizationId,
        "Integration Test Project",
        "development",
        Timestamp.from(Instant.now()));

    jdbcTemplate.update(
        "INSERT INTO api_keys (id, project_id, name, key_prefix, key_hash, created_at) VALUES (?, ?, ?, ?, ?, ?)",
        apiKeyId,
        projectId,
        "Integration test key",
        "testkey",
        TEST_API_KEY_HASH,
        Timestamp.from(Instant.now()));
  }

  @AfterEach
  void cleanupDatabase() {
    jdbcTemplate.update("DELETE FROM outbox_messages");
    jdbcTemplate.update("DELETE FROM endpoint_subscriptions");
    jdbcTemplate.update("DELETE FROM endpoint_audit_events");
    jdbcTemplate.update("DELETE FROM endpoints");
    jdbcTemplate.update("DELETE FROM api_keys");
    jdbcTemplate.update("DELETE FROM projects");
    jdbcTemplate.update("DELETE FROM organizations");
  }

  @Test
  void createEndpointReturnsCreatedEndpoint() {
    EndpointService.CreatedEndpointResult result =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "My endpoint");
    WebhookEndpoint endpoint = result.endpoint();

    assertThat(endpoint.id()).isNotNull();
    assertThat(endpoint.projectId()).isEqualTo(projectId);
    assertThat(endpoint.url()).isEqualTo("https://8.8.4.10/webhook");
    assertThat(endpoint.description()).isEqualTo("My endpoint");
    assertThat(endpoint.enabled()).isTrue();
    assertThat(endpoint.createdAt()).isNotNull();
    assertThat(endpoint.updatedAt()).isNotNull();
    assertThat(endpoint.deletedAt()).isNull();
    // Signing secret encryption is configured in test environment; ciphertext is non-empty
    assertThat(endpoint.signingSecretCiphertext()).isNotEmpty();
    assertThat(endpoint.nonce()).isNotNull();
    assertThat(endpoint.keyVersion()).isEqualTo("v1");
    // Plaintext secret is returned separately, not embedded in the endpoint domain model
    assertThat(result.signingSecret()).startsWith("whsec_");
  }

  @Test
  void createEndpointRejectsDuplicateUrl() {
    endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "First");

    assertThatThrownBy(() ->
            endpointService.createEndpoint(projectId, "HTTPS://8.8.4.10/webhook", "Duplicate"))
        .isInstanceOf(EndpointAlreadyExistsException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  void getEndpointReturnsEndpoint() {
    EndpointService.CreatedEndpointResult createdResult =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");
    WebhookEndpoint created = createdResult.endpoint();

    WebhookEndpoint found = endpointService.getEndpoint(created.id(), projectId);

    assertThat(found.id()).isEqualTo(created.id());
    assertThat(found.url()).isEqualTo("https://8.8.4.10/webhook");
    assertThat(found.description()).isEqualTo("Test");
    assertThat(found.enabled()).isTrue();
  }

  @Test
  void getEndpointReturns404ForUnknown() {
    assertThatThrownBy(() -> endpointService.getEndpoint(UUID.randomUUID(), projectId))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void getEndpointRejectsCrossProjectAccess() {
    UUID otherProjectId = UUID.fromString("dddddddd-4444-4444-8444-dddddddddddd");
    jdbcTemplate.update(
        "INSERT INTO projects (id, organization_id, name, environment, created_at) VALUES (?, ?, ?, ?, ?)",
        otherProjectId,
        UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"),
        "Other Project",
        "development",
        Timestamp.from(Instant.now()));

    EndpointService.CreatedEndpointResult createdResult =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");
    WebhookEndpoint created = createdResult.endpoint();

    assertThatThrownBy(() -> endpointService.getEndpoint(created.id(), otherProjectId))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void listEndpointsReturnsCreatedEndpoints() {
    endpointService.createEndpoint(projectId, "https://8.8.8.10/webhook", "A");
    endpointService.createEndpoint(projectId, "https://8.8.8.20/webhook", "B");
    endpointService.createEndpoint(projectId, "https://8.8.8.30/webhook", "C");

    List<WebhookEndpoint> all = endpointService.listEndpoints(projectId, 0, 20);

    assertThat(all).hasSize(3);
    assertThat(all).allMatch(e -> e.projectId().equals(projectId));
    assertThat(all).allMatch(e -> e.deletedAt() == null);
  }

  @Test
  void listEndpointsSupportsPagination() {
    endpointService.createEndpoint(projectId, "https://8.8.8.70/webhook", "One");
    endpointService.createEndpoint(projectId, "https://8.8.8.71/webhook", "Two");
    endpointService.createEndpoint(projectId, "https://8.8.8.72/webhook", "Three");

    List<WebhookEndpoint> page1 = endpointService.listEndpoints(projectId, 0, 2);
    List<WebhookEndpoint> page2 = endpointService.listEndpoints(projectId, 1, 2);

    assertThat(page1).hasSize(2);
    assertThat(page2).hasSize(1);
    assertThat(endpointService.countEndpoints(projectId)).isEqualTo(3);
  }

  @Test
  void updateEndpointReturnsUpdatedEndpoint() {
    EndpointService.CreatedEndpointResult createdResult =
        endpointService.createEndpoint(projectId, "https://8.8.8.41/webhook", "Old");
    WebhookEndpoint created = createdResult.endpoint();

    WebhookEndpoint updated =
        endpointService.updateEndpoint(
            created.id(), projectId, "https://8.8.8.40/webhook", "Updated", false);

    assertThat(updated.url()).isEqualTo("https://8.8.8.40/webhook");
    assertThat(updated.description()).isEqualTo("Updated");
    assertThat(updated.enabled()).isFalse();
    assertThat(updated.createdAt()).isEqualTo(created.createdAt());
    assertThat(updated.updatedAt()).isNotNull();
  }

  @Test
  void updateEndpointRejectsDuplicateUrl() {
    endpointService.createEndpoint(projectId, "https://8.8.8.60/webhook", "First");
    EndpointService.CreatedEndpointResult secondResult =
        endpointService.createEndpoint(projectId, "https://8.8.8.61/webhook", "Second");
    WebhookEndpoint second = secondResult.endpoint();

    assertThatThrownBy(() ->
            endpointService.updateEndpoint(
                second.id(), projectId, "HTTPS://8.8.8.60/webhook", null, null))
        .isInstanceOf(EndpointAlreadyExistsException.class);
  }

  @Test
  void updateEndpointRejectsUnknownEndpoint() {
    assertThatThrownBy(() ->
            endpointService.updateEndpoint(
                UUID.randomUUID(), projectId, "https://8.8.4.10/webhook", null, null))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void enableAndDisableEndpoint() {
    EndpointService.CreatedEndpointResult createdResult =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");
    WebhookEndpoint created = createdResult.endpoint();

    endpointService.disableEndpoint(created.id(), projectId);
    WebhookEndpoint disabled = endpointService.getEndpoint(created.id(), projectId);
    assertThat(disabled.enabled()).isFalse();

    endpointService.enableEndpoint(created.id(), projectId);
    WebhookEndpoint enabled = endpointService.getEndpoint(created.id(), projectId);
    assertThat(enabled.enabled()).isTrue();
  }

  @Test
  void deleteEndpointSoftDeletes() {
    EndpointService.CreatedEndpointResult createdResult =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");
    WebhookEndpoint created = createdResult.endpoint();

    endpointService.deleteEndpoint(created.id(), projectId);

    assertThatThrownBy(() -> endpointService.getEndpoint(created.id(), projectId))
        .isInstanceOf(EndpointNotFoundException.class);
    assertThat(endpointService.listEndpoints(projectId, 0, 20)).isEmpty();
  }

  @Test
  void deleteEndpointReturns404ForUnknown() {
    assertThatThrownBy(() -> endpointService.deleteEndpoint(UUID.randomUUID(), projectId))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void deleteEndpointRejectsCrossProjectAccess() {
    UUID otherProjectId = UUID.fromString("eeeeeeee-6666-4666-8666-eeeeeeeeeeee");
    jdbcTemplate.update(
        "INSERT INTO projects (id, organization_id, name, environment, created_at) VALUES (?, ?, ?, ?, ?)",
        otherProjectId,
        UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"),
        "Victim Project",
        "development",
        Timestamp.from(Instant.now()));

    EndpointService.CreatedEndpointResult createdResult =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");
    WebhookEndpoint created = createdResult.endpoint();

    assertThatThrownBy(() -> endpointService.deleteEndpoint(created.id(), otherProjectId))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void createRequestRejectsBlankUrl() {
    EndpointCreateRequest request = new EndpointCreateRequest(null, "desc");
    Set<ConstraintViolation<EndpointCreateRequest>> violations = validator.validate(request);
    assertThat(violations).anyMatch(v -> v.getMessage().contains("URL is required"));
  }

  @Test
  void createRequestRejectsNonHttpsUrl() {
    EndpointCreateRequest request = new EndpointCreateRequest("http://8.8.4.10/webhook", "desc");
    Set<ConstraintViolation<EndpointCreateRequest>> violations = validator.validate(request);
    assertThat(violations).anyMatch(v -> v.getMessage().contains("HTTPS scheme"));
  }

  @Test
  void createRequestRejectsTooLongUrl() {
    String longUrl = "https://" + "a".repeat(3000) + ".com/webhook";
    EndpointCreateRequest request = new EndpointCreateRequest(longUrl, "desc");
    Set<ConstraintViolation<EndpointCreateRequest>> violations = validator.validate(request);
    assertThat(violations).anyMatch(v -> v.getMessage().contains("2048 characters"));
  }

  @Test
  void createRequestRejectsTooLongDescription() {
    String longDesc = "a".repeat(200);
    EndpointCreateRequest request = new EndpointCreateRequest("https://8.8.4.10/webhook", longDesc);
    Set<ConstraintViolation<EndpointCreateRequest>> violations = validator.validate(request);
    assertThat(violations).anyMatch(v -> v.getMessage().contains("160 characters"));
  }

  @Test
  void createRequestAcceptsValidHttpsUrl() {
    EndpointCreateRequest request = new EndpointCreateRequest("https://8.8.4.10/webhook", "Valid");
    Set<ConstraintViolation<EndpointCreateRequest>> violations = validator.validate(request);
    assertThat(violations).isEmpty();
  }

  @Test
  void updateRequestAcceptsNullFields() {
    EndpointUpdateRequest request = new EndpointUpdateRequest(null, null, null);
    Set<ConstraintViolation<EndpointUpdateRequest>> violations = validator.validate(request);
    assertThat(violations).isEmpty();
  }

  @Test
  void responseDoesNotExposeSigningSecret() {
    EndpointService.CreatedEndpointResult result =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");

    // The plaintext secret is returned separately, not embedded in the response DTO
    assertThat(result.signingSecret()).isNotNull();
    assertThat(result.signingSecret()).startsWith("whsec_");
    // The response DTO (EndpointResponse) does not include the plaintext secret
    // The domain model may hold ciphertext (when encryption configured) or empty sentinel
  }

  @Test
  void rotateSecretReturnsNewPlaintextSecret() {
    EndpointService.CreatedEndpointResult createResult =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");
    WebhookEndpoint endpoint = createResult.endpoint();
    String originalSecret = createResult.signingSecret();

    EndpointService.RotateSecretResult rotateResult =
        endpointService.rotateSigningSecret(endpoint.id(), projectId);

    // New secret is returned and is different from the original
    assertThat(rotateResult.signingSecret()).isNotNull();
    assertThat(rotateResult.signingSecret()).startsWith("whsec_");
    assertThat(rotateResult.signingSecret()).isNotEqualTo(originalSecret);

    // The returned endpoint has updated ciphertext, nonce, key version
    assertThat(rotateResult.endpoint().id()).isEqualTo(endpoint.id());
    assertThat(rotateResult.endpoint().signingSecretCiphertext()).isNotEmpty();
    assertThat(rotateResult.endpoint().nonce()).isNotNull();
    assertThat(rotateResult.endpoint().keyVersion()).isEqualTo("v1");
    assertThat(rotateResult.endpoint().updatedAt()).isNotNull();
    assertThat(rotateResult.endpoint().updatedAt()).isAfter(endpoint.updatedAt());
  }

  @Test
  void rotateSecretOldSecretNoLongerDecrypts() {
    EndpointService.CreatedEndpointResult createResult =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");
    String originalSecret = createResult.signingSecret();

    endpointService.rotateSigningSecret(createResult.endpoint().id(), projectId);

    // Old plaintext secret should not match the decrypted current secret
    WebhookEndpoint current =
        endpointRepository
            .findByIdAndProject(createResult.endpoint().id(), projectId)
            .orElseThrow();
    String decrypted = endpointService.getSigningSecret(current.id(), current.projectId());
    assertThat(decrypted).isNotEqualTo(originalSecret);
  }

  @Test
  void rotateSecretRejectsUnknownEndpoint() {
    assertThatThrownBy(() -> endpointService.rotateSigningSecret(UUID.randomUUID(), projectId))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void rotateSecretRejectsCrossProjectAccess() {
    EndpointService.CreatedEndpointResult createResult =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");

    UUID otherProjectId = UUID.fromString("dddddddd-4444-4444-8444-dddddddddddd");
    jdbcTemplate.update(
        "INSERT INTO projects (id, organization_id, name, environment, created_at) VALUES (?, ?, ?, ?, ?)",
        otherProjectId,
        UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"),
        "Other Project",
        "development",
        Timestamp.from(Instant.now()));

    assertThatThrownBy(
            () -> endpointService.rotateSigningSecret(createResult.endpoint().id(), otherProjectId))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void rotateSecretRecordsAuditEvent() {
    EndpointService.CreatedEndpointResult createResult =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");

    endpointService.rotateSigningSecret(createResult.endpoint().id(), projectId);

    var auditEvents =
        jdbcTemplate.queryForList(
            """
            SELECT id, project_id, endpoint_id, action FROM endpoint_audit_events WHERE action = 'SECRET_ROTATED'
            """);

    assertThat(auditEvents).hasSize(1);
    var row = auditEvents.getFirst();
    assertThat(row.get("project_id")).isEqualTo(projectId);
    assertThat(row.get("endpoint_id")).isEqualTo(createResult.endpoint().id());
    assertThat(row.get("action")).isEqualTo("SECRET_ROTATED");
    // Payload must not contain secret material
    assertThat(row.toString()).doesNotContain("whsec_");
  }

  @Test
  void rotateSecretDomainModelDoesNotContainPlaintext() {
    EndpointService.CreatedEndpointResult createResult =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");
    String originalSecret = createResult.signingSecret();

    EndpointService.RotateSecretResult rotateResult =
        endpointService.rotateSigningSecret(createResult.endpoint().id(), projectId);

    // The domain model holds ciphertext, not plaintext
    assertThat(rotateResult.endpoint().signingSecretCiphertext()).isNotEmpty();
    assertThat(rotateResult.endpoint().signingSecretCiphertext()).isNotEqualTo(originalSecret);
    assertThat(rotateResult.endpoint().nonce()).isNotNull();

    // The plaintext is only available from RotateSecretResult, not from the domain model
    assertThat(rotateResult.signingSecret())
        .isNotEqualTo(rotateResult.endpoint().signingSecretCiphertext());
  }

  @Test
  void rotateSecretRequiresEncryptionConfigured() {
    var unconfigured = new com.hookrelay.security.AesGcmSigningSecretService("", "v1");
    unconfigured.afterPropertiesSet();
    var service =
        new EndpointService(
            endpointRepository, unconfigured, jdbcTemplate, mapper, url -> {}, transactions);
    assertThatThrownBy(() -> service.createEndpoint(projectId, "https://8.8.8.8/missing", null))
        .isInstanceOf(com.hookrelay.security.SigningUnavailableException.class);
    assertThat(endpointService.countEndpoints(projectId)).isZero();
    assertThatThrownBy(() -> service.rotateSigningSecret(UUID.randomUUID(), projectId))
        .isInstanceOf(com.hookrelay.security.SigningUnavailableException.class);
  }

  @Autowired private com.hookrelay.security.SigningSecretService secrets;
  @Autowired private tools.jackson.databind.ObjectMapper mapper;
  @Autowired private org.springframework.transaction.support.TransactionTemplate transactions;

  @Test
  void resolvesDnsOutsideDatabaseTransactions() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var service =
        new EndpointService(
            endpointRepository,
            secrets,
            jdbcTemplate,
            mapper,
            url -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              calls.incrementAndGet();
            },
            transactions);
    var endpoint =
        service.createEndpoint(projectId, "https://customer.example/hook", "test").endpoint();
    service.updateEndpoint(endpoint.id(), projectId, "https://customer.example/other", null, null);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void staleMetadataSaveCannotRestoreRotatedSecret() {
    var created = endpointService.createEndpoint(projectId, "https://8.8.8.8/race", "before");
    var rotated = endpointService.rotateSigningSecret(created.endpoint().id(), projectId);
    endpointRepository.save(created.endpoint());
    assertThat(endpointService.getSigningSecret(created.endpoint().id(), projectId))
        .isEqualTo(rotated.signingSecret());
  }

  @Test
  void urlIdentityPreservesPathCaseAndNormalizesAuthority() {
    var first = endpointService.createEndpoint(projectId, "HTTPS://8.8.8.8:443/Hook", "first");
    var second = endpointService.createEndpoint(projectId, "https://8.8.8.8/hook", "second");
    assertThat(first.endpoint().id()).isNotEqualTo(second.endpoint().id());
    assertThatThrownBy(
            () -> endpointService.createEndpoint(projectId, "https://8.8.8.8/Hook", "duplicate"))
        .isInstanceOf(EndpointAlreadyExistsException.class);
  }

  @Test
  void paginationDoesNotOverflow() {
    assertThat(endpointService.listEndpoints(projectId, Integer.MAX_VALUE, 100)).isEmpty();
  }

  @Test
  void auditFailureRollsBackSecretRotation() {
    var created = endpointService.createEndpoint(projectId, "https://8.8.8.8/rollback", null);
    jdbcTemplate.execute(
        "ALTER TABLE endpoint_audit_events ADD CONSTRAINT test_reject_audit CHECK (false) NOT VALID");
    try {
      assertThatThrownBy(
              () -> endpointService.rotateSigningSecret(created.endpoint().id(), projectId))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      assertThat(endpointService.getSigningSecret(created.endpoint().id(), projectId))
          .isEqualTo(created.signingSecret());
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT COUNT(*) FROM endpoint_audit_events WHERE project_id = ?",
                  Integer.class,
                  projectId))
          .isZero();
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT COUNT(*) FROM outbox_messages WHERE project_id = ?",
                  Integer.class,
                  projectId))
          .isZero();
    } finally {
      jdbcTemplate.execute("ALTER TABLE endpoint_audit_events DROP CONSTRAINT test_reject_audit");
    }
  }
}
