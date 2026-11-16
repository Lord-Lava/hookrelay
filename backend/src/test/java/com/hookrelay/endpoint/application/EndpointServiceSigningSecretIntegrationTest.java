package com.hookrelay.endpoint.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hookrelay.endpoint.domain.WebhookEndpoint;
import com.hookrelay.endpoint.repository.WebhookEndpointRepository;
import com.hookrelay.security.SigningSecretService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
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

@SpringBootTest
@Testcontainers
class EndpointServiceSigningSecretIntegrationTest {

  private static final String TEST_API_KEY = "hr_live_testkey_signing_secret_integration_value";

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.7-alpine"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("test.context.marker", () -> "endpoint-signing-secret-integration");
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("hookrelay.outbox.enabled", () -> "false");
    registry.add(
        "hookrelay.signing.encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    registry.add("hookrelay.signing.key-version", () -> "v1");
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private WebhookEndpointRepository endpointRepository;
  @Autowired private EndpointService endpointService;
  @Autowired private SigningSecretService signingSecretService;

  private UUID organizationId;
  private UUID projectId;

  @BeforeEach
  void seedDatabase() {
    organizationId = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
    projectId = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");

    jdbcTemplate.update(
        "INSERT INTO organizations (id, name, created_at) VALUES (?, ?, ?)",
        organizationId,
        "Signing Secret Test Org",
        Timestamp.from(Instant.now()));

    jdbcTemplate.update(
        "INSERT INTO projects (id, organization_id, name, environment, created_at) VALUES (?, ?, ?, ?, ?)",
        projectId,
        organizationId,
        "Signing Secret Test Project",
        "development",
        Timestamp.from(Instant.now()));
  }

  @AfterEach
  void cleanupDatabase() {
    jdbcTemplate.update("DELETE FROM endpoint_audit_events");
    jdbcTemplate.update("DELETE FROM endpoints");
    jdbcTemplate.update("DELETE FROM api_keys");
    jdbcTemplate.update("DELETE FROM projects");
    jdbcTemplate.update("DELETE FROM organizations");
  }

  @Test
  void createEndpointReturnsPlaintextSecretOnce() {
    EndpointService.CreatedEndpointResult result =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");

    assertThat(result.endpoint().id()).isNotNull();
    assertThat(result.signingSecret()).startsWith("whsec_");
    assertThat(result.signingSecret()).isNotEmpty();
  }

  @Test
  void plaintextSecretIsNotStoredInDatabase() {
    EndpointService.CreatedEndpointResult result =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");

    WebhookEndpoint stored =
        endpointRepository.findByIdAndProject(result.endpoint().id(), projectId).orElseThrow();

    // Ciphertext is stored, not plaintext
    assertThat(stored.signingSecretCiphertext()).isNotEmpty();
    assertThat(stored.signingSecretCiphertext()).isNotEqualTo(result.signingSecret());
    // Nonce and key version are stored
    assertThat(stored.nonce()).isNotNull();
    assertThat(stored.keyVersion()).isEqualTo("v1");
    // Plaintext is not recoverable from the stored fields alone without the encryption key
    assertThat(stored.signingSecretCiphertext()).isNotEqualTo(result.signingSecret());
  }

  @Test
  void getSigningSecretDecryptsStoredCiphertext() {
    EndpointService.CreatedEndpointResult result =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");

    String decrypted = endpointService.getSigningSecret(result.endpoint().id(), projectId);
    assertThat(decrypted).isEqualTo(result.signingSecret());
  }

  @Test
  void getSigningSecretRejectsUnknownEndpoint() {
    assertThatThrownBy(() -> endpointService.getSigningSecret(UUID.randomUUID(), projectId))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void encryptDecryptRoundTripThroughService() {
    String plaintext = "whsec_integration_test_secret";
    SigningSecretService.EncryptedSecret encrypted = signingSecretService.encrypt(plaintext);

    String decrypted =
        signingSecretService.decrypt(encrypted.ciphertext(), encrypted.nonce(), "v1");
    assertThat(decrypted).isEqualTo(plaintext);
  }

  @Test
  void listEndpointsDoesNotExposePlaintextSecret() {
    endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");

    var endpoints = endpointService.listEndpoints(projectId, 0, 20);
    assertThat(endpoints).hasSize(1);
    // listEndpoints returns WebhookEndpoint domain objects which hold ciphertext, not plaintext
    assertThat(endpoints.get(0).signingSecretCiphertext()).isNotEmpty();
    // There is no way to get plaintext from the returned domain object
  }

  @Test
  void crossProjectCannotReadSigningSecret() {
    EndpointService.CreatedEndpointResult result =
        endpointService.createEndpoint(projectId, "https://8.8.4.10/webhook", "Test");

    UUID otherProjectId = UUID.fromString("dddddddd-4444-4444-8444-dddddddddddd");
    jdbcTemplate.update(
        "INSERT INTO projects (id, organization_id, name, environment, created_at) VALUES (?, ?, ?, ?, ?)",
        otherProjectId,
        organizationId,
        "Other Project",
        "development",
        Timestamp.from(Instant.now()));

    assertThatThrownBy(
            () -> endpointService.getSigningSecret(result.endpoint().id(), otherProjectId))
        .isInstanceOf(EndpointNotFoundException.class);
  }
}
