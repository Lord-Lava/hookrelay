package com.hookrelay.endpoint.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hookrelay.endpoint.domain.EndpointSubscription;
import com.hookrelay.endpoint.domain.WebhookEndpoint;
import com.hookrelay.endpoint.repository.EndpointSubscriptionRepository;
import com.hookrelay.endpoint.repository.WebhookEndpointRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

@SpringBootTest
@Testcontainers
class EndpointSubscriptionServiceIntegrationTest {

  private static final String TEST_API_KEY =
      "hr_live_testkey_subscription_integration_secret_value";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.7-alpine"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("test.context.marker", () -> "endpoint-subscription-integration");
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("hookrelay.outbox.enabled", () -> "false");
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WebhookEndpointRepository endpointRepository;
  @Autowired private EndpointSubscriptionRepository subscriptionRepository;
  @Autowired private EndpointSubscriptionService subscriptionService;

  private UUID organizationId;
  private UUID projectId;
  private UUID otherProjectId;
  private UUID endpointId;
  private UUID otherEndpointId;

  @BeforeEach
  void seedDatabase() {
    organizationId = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
    projectId = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");
    otherProjectId = UUID.fromString("dddddddd-4444-4444-8444-dddddddddddd");

    jdbcTemplate.update(
        "INSERT INTO organizations (id, name, created_at) VALUES (?, ?, ?)",
        organizationId,
        "Subscription Test Org",
        Timestamp.from(Instant.now()));

    jdbcTemplate.update(
        "INSERT INTO projects (id, organization_id, name, environment, created_at) VALUES (?, ?, ?, ?, ?)",
        projectId,
        organizationId,
        "Subscription Test Project",
        "development",
        Timestamp.from(Instant.now()));

    jdbcTemplate.update(
        "INSERT INTO projects (id, organization_id, name, environment, created_at) VALUES (?, ?, ?, ?, ?)",
        otherProjectId,
        organizationId,
        "Other Project",
        "development",
        Timestamp.from(Instant.now()));

    WebhookEndpoint endpoint =
        new WebhookEndpoint(
            null, projectId, "https://example.com/webhook", "Test", "", true, null, null, null);
    endpointId = endpointRepository.save(endpoint).id();

    WebhookEndpoint otherEndpoint =
        new WebhookEndpoint(
            null,
            otherProjectId,
            "https://other.example.com/webhook",
            "Other",
            "",
            true,
            null,
            null,
            null);
    otherEndpointId = endpointRepository.save(otherEndpoint).id();
  }

  @AfterEach
  void cleanupDatabase() {
    jdbcTemplate.update("DELETE FROM endpoint_subscriptions");
    jdbcTemplate.update("DELETE FROM endpoints");
    jdbcTemplate.update("DELETE FROM api_keys");
    jdbcTemplate.update("DELETE FROM projects");
    jdbcTemplate.update("DELETE FROM organizations");
  }

  @Test
  void createSubscriptionReturnsCreatedSubscription() {
    EndpointSubscriptionService.SubscriptionResult result =
        subscriptionService.createOrUpdateSubscription(projectId, endpointId, "payment.succeeded");

    assertThat(result.created()).isTrue();
    assertThat(result.response().id()).isNotNull();
    assertThat(result.response().endpointId()).isEqualTo(endpointId);
    assertThat(result.response().eventType()).isEqualTo("payment.succeeded");
    assertThat(result.response().createdAt()).isNotNull();
  }

  @Test
  void createSubscriptionIsIdempotent() {
    EndpointSubscriptionService.SubscriptionResult first =
        subscriptionService.createOrUpdateSubscription(projectId, endpointId, "payment.succeeded");
    EndpointSubscriptionService.SubscriptionResult second =
        subscriptionService.createOrUpdateSubscription(projectId, endpointId, "payment.succeeded");

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.response().id()).isEqualTo(first.response().id());
    assertThat(second.response().eventType()).isEqualTo("payment.succeeded");
  }

  @Test
  void createSubscriptionRejectsUnknownEndpoint() {
    assertThatThrownBy(() ->
            subscriptionService.createOrUpdateSubscription(
                projectId, UUID.randomUUID(), "payment.succeeded"))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void createSubscriptionRejectsCrossProjectEndpoint() {
    assertThatThrownBy(() ->
            subscriptionService.createOrUpdateSubscription(
                otherProjectId, endpointId, "payment.succeeded"))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void createSubscriptionRejectsBlankEventType() {
    assertThatThrownBy(
            () -> subscriptionService.createOrUpdateSubscription(projectId, endpointId, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventType must not be blank");
  }

  @Test
  void createSubscriptionRejectsTooLongEventType() {
    String longType = "a".repeat(129);
    assertThatThrownBy(
            () -> subscriptionService.createOrUpdateSubscription(projectId, endpointId, longType))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("128 characters");
  }

  @Test
  void createSubscriptionRejectsInvalidEventTypeFormat() {
    assertThatThrownBy(() ->
            subscriptionService.createOrUpdateSubscription(projectId, endpointId, "INVALID_TYPE"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lowercase");
  }

  @Test
  void createSubscriptionAcceptsValidEventTypes() {
    EndpointSubscriptionService.SubscriptionResult r1 =
        subscriptionService.createOrUpdateSubscription(projectId, endpointId, "payment.succeeded");
    EndpointSubscriptionService.SubscriptionResult r2 =
        subscriptionService.createOrUpdateSubscription(projectId, endpointId, "refund.processed");
    EndpointSubscriptionService.SubscriptionResult r3 =
        subscriptionService.createOrUpdateSubscription(projectId, endpointId, "order-created");
    EndpointSubscriptionService.SubscriptionResult r4 =
        subscriptionService.createOrUpdateSubscription(projectId, endpointId, "user.signup.v1");

    assertThat(r1.created()).isTrue();
    assertThat(r2.created()).isTrue();
    assertThat(r3.created()).isTrue();
    assertThat(r4.created()).isTrue();
  }

  @Test
  void deleteSubscriptionRemovesSubscription() {
    subscriptionService.createOrUpdateSubscription(projectId, endpointId, "payment.succeeded");

    subscriptionService.deleteSubscription(projectId, endpointId, "payment.succeeded");

    assertThatThrownBy(() ->
            subscriptionService.deleteSubscription(projectId, endpointId, "payment.succeeded"))
        .isInstanceOf(SubscriptionNotFoundException.class);
  }

  @Test
  void deleteSubscriptionRejectsUnknownEndpoint() {
    assertThatThrownBy(() ->
            subscriptionService.deleteSubscription(
                projectId, UUID.randomUUID(), "payment.succeeded"))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void deleteSubscriptionRejectsNonexistentSubscription() {
    assertThatThrownBy(
            () -> subscriptionService.deleteSubscription(projectId, endpointId, "nonexistent.type"))
        .isInstanceOf(SubscriptionNotFoundException.class);
  }

  @Test
  void deleteSubscriptionRejectsCrossProjectAccess() {
    subscriptionService.createOrUpdateSubscription(projectId, endpointId, "payment.succeeded");

    assertThatThrownBy(() ->
            subscriptionService.deleteSubscription(otherProjectId, endpointId, "payment.succeeded"))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void listSubscriptionsReturnsCreatedSubscriptions() {
    subscriptionService.createOrUpdateSubscription(projectId, endpointId, "payment.succeeded");
    subscriptionService.createOrUpdateSubscription(projectId, endpointId, "refund.processed");
    subscriptionService.createOrUpdateSubscription(projectId, endpointId, "order.created");

    List<EndpointSubscription> all =
        subscriptionService.listSubscriptions(projectId, endpointId, 0, 20);

    assertThat(all).hasSize(3);
    assertThat(all).allMatch(s -> s.endpointId().equals(endpointId));
    assertThat(all).allMatch(s -> s.deletedAt() == null);
  }

  @Test
  void listSubscriptionsSupportsPagination() {
    subscriptionService.createOrUpdateSubscription(projectId, endpointId, "event.one");
    subscriptionService.createOrUpdateSubscription(projectId, endpointId, "event.two");
    subscriptionService.createOrUpdateSubscription(projectId, endpointId, "event.three");

    List<EndpointSubscription> page1 =
        subscriptionService.listSubscriptions(projectId, endpointId, 0, 2);
    List<EndpointSubscription> page2 =
        subscriptionService.listSubscriptions(projectId, endpointId, 1, 2);

    assertThat(page1).hasSize(2);
    assertThat(page2).hasSize(1);
    assertThat(subscriptionService.countSubscriptions(projectId, endpointId)).isEqualTo(3);
  }

  @Test
  void listSubscriptionsRejectsUnknownEndpoint() {
    assertThatThrownBy(
            () -> subscriptionService.listSubscriptions(projectId, UUID.randomUUID(), 0, 20))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void listSubscriptionsRejectsCrossProjectAccess() {
    subscriptionService.createOrUpdateSubscription(projectId, endpointId, "payment.succeeded");

    assertThatThrownBy(
            () -> subscriptionService.listSubscriptions(otherProjectId, endpointId, 0, 20))
        .isInstanceOf(EndpointNotFoundException.class);
  }

  @Test
  void softDeletedSubscriptionDoesNotBlockReSubscription() {
    subscriptionService.createOrUpdateSubscription(projectId, endpointId, "payment.succeeded");
    subscriptionService.deleteSubscription(projectId, endpointId, "payment.succeeded");

    EndpointSubscriptionService.SubscriptionResult result =
        subscriptionService.createOrUpdateSubscription(projectId, endpointId, "payment.succeeded");

    assertThat(result.created()).isTrue();
    assertThat(result.response().eventType()).isEqualTo("payment.succeeded");
  }
}
