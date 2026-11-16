package com.hookrelay.endpoint.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hookrelay.endpoint.domain.EndpointSubscription;
import com.hookrelay.endpoint.domain.WebhookEndpoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class EndpointRepositoryIntegrationTest {

  private static final UUID ORGANIZATION_ID =
      UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
  private static final UUID PROJECT_ID_A = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");
  private static final UUID PROJECT_ID_B = UUID.fromString("cccccccc-3333-4333-8333-cccccccccccc");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.7-alpine"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    // Unique marker ensures this test class gets its own cached application context
    registry.add("test.context.marker", () -> "endpoint-repository-integration");
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("hookrelay.outbox.enabled", () -> "false");
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private WebhookEndpointRepository endpointRepository;
  @Autowired private EndpointSubscriptionRepository subscriptionRepository;

  @BeforeEach
  void seedDatabase() {
    jdbcTemplate.update(
        "INSERT INTO organizations (id, name, created_at) VALUES (?, ?, ?)",
        ORGANIZATION_ID,
        "Test Organization",
        Timestamp.from(Instant.now()));

    jdbcTemplate.update(
        "INSERT INTO projects (id, organization_id, name, environment, created_at) VALUES (?, ?, ?, ?, ?)",
        PROJECT_ID_A,
        ORGANIZATION_ID,
        "Project A",
        "development",
        Timestamp.from(Instant.now()));

    jdbcTemplate.update(
        "INSERT INTO projects (id, organization_id, name, environment, created_at) VALUES (?, ?, ?, ?, ?)",
        PROJECT_ID_B,
        ORGANIZATION_ID,
        "Project B",
        "development",
        Timestamp.from(Instant.now()));
  }

  @AfterEach
  void cleanupDatabase() {
    jdbcTemplate.update("DELETE FROM endpoint_subscriptions");
    jdbcTemplate.update("DELETE FROM endpoints");
    jdbcTemplate.update("DELETE FROM projects");
    jdbcTemplate.update("DELETE FROM organizations");
  }

  private WebhookEndpoint insertEndpoint(UUID projectId, String url, boolean enabled) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO endpoints (id, project_id, url, description, signing_secret_ciphertext,
                               signing_secret_nonce, signing_secret_key_version, enabled, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
        """,
        id,
        projectId,
        url,
        "Test endpoint",
        "encrypted-ciphertext-placeholder",
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
        "v1",
        enabled);
    return new WebhookEndpoint(
        id,
        projectId,
        url,
        "Test endpoint",
        "encrypted-ciphertext-placeholder",
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
        "v1",
        enabled,
        Instant.now(),
        Instant.now(),
        null);
  }

  @Test
  void findByIdAndProjectReturnsEndpointWhenOwnedByProject() {
    WebhookEndpoint expected = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);

    var found = endpointRepository.findByIdAndProject(expected.id(), PROJECT_ID_A);

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(expected.id());
    assertThat(found.get().projectId()).isEqualTo(PROJECT_ID_A);
    assertThat(found.get().url()).isEqualTo("https://a.example.com/webhook");
    assertThat(found.get().enabled()).isTrue();
    assertThat(found.get().deletedAt()).isNull();
  }

  @Test
  void findByIdAndProjectReturnsEmptyForDifferentProject() {
    WebhookEndpoint endpoint = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);

    var found = endpointRepository.findByIdAndProject(endpoint.id(), PROJECT_ID_B);

    assertThat(found).isEmpty();
  }

  @Test
  void findByIdAndProjectReturnsEmptyForSoftDeletedEndpoint() {
    WebhookEndpoint endpoint = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);
    endpointRepository.softDelete(endpoint.id(), PROJECT_ID_A);

    var found = endpointRepository.findByIdAndProject(endpoint.id(), PROJECT_ID_A);

    assertThat(found).isEmpty();
  }

  @Test
  void findByProjectReturnsOnlyEndpointsForThatProject() {
    insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook-a", true);
    insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook-b", true);
    insertEndpoint(PROJECT_ID_B, "https://b.example.com/webhook", true);

    List<WebhookEndpoint> endpoints = endpointRepository.findByProject(PROJECT_ID_A);

    assertThat(endpoints).hasSize(2);
    assertThat(endpoints).allMatch(e -> e.projectId().equals(PROJECT_ID_A));
    assertThat(endpoints).allMatch(e -> e.deletedAt() == null);
  }

  @Test
  void findByProjectExcludesSoftDeletedEndpoints() {
    WebhookEndpoint active = insertEndpoint(PROJECT_ID_A, "https://a.example.com/active", true);
    WebhookEndpoint deleted = insertEndpoint(PROJECT_ID_A, "https://a.example.com/deleted", true);
    endpointRepository.softDelete(deleted.id(), PROJECT_ID_A);

    List<WebhookEndpoint> endpoints = endpointRepository.findByProject(PROJECT_ID_A);

    assertThat(endpoints).hasSize(1);
    assertThat(endpoints.get(0).id()).isEqualTo(active.id());
  }

  @Test
  void softDeleteMarksDeletedAtAndExcludesFromQueries() {
    WebhookEndpoint endpoint = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);

    int updated = endpointRepository.softDelete(endpoint.id(), PROJECT_ID_A);

    assertThat(updated).isEqualTo(1);
    assertThat(endpointRepository.findByIdAndProject(endpoint.id(), PROJECT_ID_A)).isEmpty();
    assertThat(endpointRepository.findByProject(PROJECT_ID_A)).isEmpty();
  }

  @Test
  void softDeleteReturnsZeroForDifferentProject() {
    WebhookEndpoint endpoint = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);

    int updated = endpointRepository.softDelete(endpoint.id(), PROJECT_ID_B);

    assertThat(updated).isEqualTo(0);
    assertThat(endpointRepository.findByIdAndProject(endpoint.id(), PROJECT_ID_A)).isPresent();
  }

  @Test
  void saveInsertsNewEndpoint() {
    UUID projectId = PROJECT_ID_A;
    WebhookEndpoint toCreate =
        new WebhookEndpoint(
            null,
            projectId,
            "https://new.example.com/webhook",
            "New endpoint",
            "encrypted-ciphertext",
            new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
            "v1",
            true,
            null,
            null,
            null);

    WebhookEndpoint saved = endpointRepository.save(toCreate);

    assertThat(saved.id()).isNotNull();
    assertThat(saved.projectId()).isEqualTo(projectId);
    assertThat(saved.url()).isEqualTo("https://new.example.com/webhook");
    assertThat(saved.enabled()).isTrue();
    assertThat(saved.createdAt()).isNotNull();
    assertThat(saved.updatedAt()).isNotNull();
    assertThat(saved.deletedAt()).isNull();

    var found = endpointRepository.findByIdAndProject(saved.id(), projectId);
    assertThat(found).isPresent();
    assertThat(found.get().url()).isEqualTo("https://new.example.com/webhook");
  }

  @Test
  void saveUpdatesExistingEndpoint() {
    WebhookEndpoint inserted =
        insertEndpoint(PROJECT_ID_A, "https://old.example.com/webhook", true);

    // Fetch the actual persisted state (DB-generated timestamps)
    WebhookEndpoint beforeUpdate =
        endpointRepository.findByIdAndProject(inserted.id(), PROJECT_ID_A).orElseThrow();

    WebhookEndpoint updated =
        new WebhookEndpoint(
            beforeUpdate.id(),
            beforeUpdate.projectId(),
            "https://new.example.com/webhook",
            "Updated description",
            beforeUpdate.signingSecretCiphertext(),
            beforeUpdate.nonce(),
            beforeUpdate.keyVersion(),
            false,
            beforeUpdate.createdAt(),
            beforeUpdate.updatedAt(),
            null);

    WebhookEndpoint result = endpointRepository.save(updated);

    assertThat(result.url()).isEqualTo("https://new.example.com/webhook");
    assertThat(result.description()).isEqualTo("Updated description");
    assertThat(result.enabled()).isFalse();
    assertThat(result.createdAt()).isEqualTo(beforeUpdate.createdAt());

    var found = endpointRepository.findByIdAndProject(inserted.id(), PROJECT_ID_A);
    assertThat(found).isPresent();
    assertThat(found.get().url()).isEqualTo("https://new.example.com/webhook");
    assertThat(found.get().enabled()).isFalse();
  }

  @Test
  void findByEndpointIdAndProjectReturnsSubscription() {
    WebhookEndpoint endpoint = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);
    UUID subscriptionId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO endpoint_subscriptions (id, endpoint_id, event_type, created_at)
        VALUES (?, ?, ?, now())
        """,
        subscriptionId,
        endpoint.id(),
        "payment.succeeded");

    var found =
        subscriptionRepository.findByIdAndEndpointIdAndProject(
            subscriptionId, endpoint.id(), PROJECT_ID_A);

    assertThat(found).isPresent();
    assertThat(found.get().eventType()).isEqualTo("payment.succeeded");
    assertThat(found.get().endpointId()).isEqualTo(endpoint.id());
  }

  @Test
  void findByEndpointIdAndProjectReturnsEmptyForDifferentProject() {
    WebhookEndpoint endpoint = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);
    UUID subscriptionId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO endpoint_subscriptions (id, endpoint_id, event_type, created_at)
        VALUES (?, ?, ?, now())
        """,
        subscriptionId,
        endpoint.id(),
        "payment.succeeded");

    var found =
        subscriptionRepository.findByIdAndEndpointIdAndProject(
            subscriptionId, endpoint.id(), PROJECT_ID_B);

    assertThat(found).isEmpty();
  }

  @Test
  void findByEndpointIdAndEventTypeAndProjectReturnsSubscription() {
    WebhookEndpoint endpoint = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);
    UUID subscriptionId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO endpoint_subscriptions (id, endpoint_id, event_type, created_at)
        VALUES (?, ?, ?, now())
        """,
        subscriptionId,
        endpoint.id(),
        "payment.succeeded");

    var found =
        subscriptionRepository.findByEndpointIdAndEventTypeAndProject(
            endpoint.id(), "payment.succeeded", PROJECT_ID_A);

    assertThat(found).isPresent();
    assertThat(found.get().eventType()).isEqualTo("payment.succeeded");
  }

  @Test
  void findByEndpointIdAndEventTypeAndProjectReturnsEmptyForDifferentEventType() {
    WebhookEndpoint endpoint = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);
    UUID subscriptionId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO endpoint_subscriptions (id, endpoint_id, event_type, created_at)
        VALUES (?, ?, ?, now())
        """,
        subscriptionId,
        endpoint.id(),
        "payment.succeeded");

    var found =
        subscriptionRepository.findByEndpointIdAndEventTypeAndProject(
            endpoint.id(), "refund.processed", PROJECT_ID_A);

    assertThat(found).isEmpty();
  }

  @Test
  void findByEndpointIdAndProjectReturnsAllSubscriptions() {
    WebhookEndpoint endpoint = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);
    jdbcTemplate.update(
        """
        INSERT INTO endpoint_subscriptions (id, endpoint_id, event_type, created_at)
        VALUES (?, ?, ?, now())
        """,
        UUID.randomUUID(),
        endpoint.id(),
        "payment.succeeded");
    jdbcTemplate.update(
        """
        INSERT INTO endpoint_subscriptions (id, endpoint_id, event_type, created_at)
        VALUES (?, ?, ?, now())
        """,
        UUID.randomUUID(),
        endpoint.id(),
        "refund.processed");

    List<EndpointSubscription> subscriptions =
        subscriptionRepository.findByEndpointIdAndProject(endpoint.id(), PROJECT_ID_A);

    assertThat(subscriptions).hasSize(2);
    assertThat(subscriptions)
        .extracting(EndpointSubscription::eventType)
        .containsExactlyInAnyOrder("payment.succeeded", "refund.processed");
  }

  @Test
  void saveInsertsNewSubscription() {
    WebhookEndpoint endpoint = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);

    EndpointSubscription toCreate =
        new EndpointSubscription(null, endpoint.id(), "payment.succeeded", null, null);

    EndpointSubscription saved = subscriptionRepository.save(toCreate);

    assertThat(saved.id()).isNotNull();
    assertThat(saved.endpointId()).isEqualTo(endpoint.id());
    assertThat(saved.eventType()).isEqualTo("payment.succeeded");
    assertThat(saved.createdAt()).isNotNull();
    assertThat(saved.deletedAt()).isNull();
  }

  @Test
  void saveSubscriptionRejectsSoftDeletedEndpoint() {
    WebhookEndpoint endpoint = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);
    endpointRepository.softDelete(endpoint.id(), PROJECT_ID_A);

    EndpointSubscription toCreate =
        new EndpointSubscription(null, endpoint.id(), "payment.succeeded", null, null);

    assertThatThrownBy(() -> subscriptionRepository.save(toCreate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("endpoint not found or is soft-deleted");
  }

  @Test
  void saveSubscriptionAllowsReSubscriptionAfterSoftDelete() {
    WebhookEndpoint endpoint = insertEndpoint(PROJECT_ID_A, "https://a.example.com/webhook", true);

    EndpointSubscription first =
        new EndpointSubscription(null, endpoint.id(), "payment.succeeded", null, null);
    EndpointSubscription firstSaved = subscriptionRepository.save(first);

    subscriptionRepository.softDeleteByEndpointIdAndProject(endpoint.id(), PROJECT_ID_A);

    // Re-subscribing to the same event type should succeed because the soft-deleted row
    // does not count against the partial unique index
    EndpointSubscription second =
        new EndpointSubscription(null, endpoint.id(), "payment.succeeded", null, null);
    EndpointSubscription secondSaved = subscriptionRepository.save(second);

    assertThat(secondSaved.id()).isNotEqualTo(firstSaved.id());
    assertThat(secondSaved.deletedAt()).isNull();
    assertThat(secondSaved.eventType()).isEqualTo("payment.succeeded");
  }
}
