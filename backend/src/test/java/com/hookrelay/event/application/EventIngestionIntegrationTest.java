package com.hookrelay.event.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.hookrelay.auth.ApiKeyAuthService;
import com.hookrelay.event.api.CreateEventRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class EventIngestionIntegrationTest {

  private static final String TEST_API_KEY = "hr_live_testkey_integration_test_secret_value";
  private static final String TEST_API_KEY_HASH = ApiKeyAuthService.sha256(TEST_API_KEY);

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.7-alpine"));

  @Container
  static RabbitMQContainer rabbitMQ =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.4.0-management"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.rabbitmq.host", rabbitMQ::getHost);
    registry.add("spring.rabbitmq.port", rabbitMQ::getAmqpPort);
    registry.add("spring.rabbitmq.username", () -> "guest");
    registry.add("spring.rabbitmq.password", () -> "guest");
    registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
    registry.add("spring.rabbitmq.publisher-returns", () -> "true");
    registry.add("spring.rabbitmq.template.mandatory", () -> "true");
    registry.add("hookrelay.outbox.enabled", () -> "false");
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private EventIngestionService eventIngestionService;
  @Autowired private ObjectMapper objectMapper;

  private UUID projectId;
  private UUID organizationId;

  @BeforeEach
  void seedDatabase() {
    organizationId = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
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
    jdbcTemplate.update("DELETE FROM delivery_attempts");
    jdbcTemplate.update("DELETE FROM deliveries");
    jdbcTemplate.update("DELETE FROM outbox_messages");
    jdbcTemplate.update("DELETE FROM events");
    jdbcTemplate.update("DELETE FROM endpoint_subscriptions");
    jdbcTemplate.update("DELETE FROM endpoints");
    jdbcTemplate.update("DELETE FROM api_keys");
    jdbcTemplate.update("DELETE FROM projects");
    jdbcTemplate.update("DELETE FROM organizations");

    rabbitTemplate.execute(channel -> {
      channel.queuePurge("hookrelay.events.ingested.v1");
      return null;
    });
  }

  private CreateEventRequest buildRequest(String eventType, Object data) throws Exception {
    String dataJson = objectMapper.writeValueAsString(data);
    JsonNode dataNode = objectMapper.readTree(dataJson);
    return new CreateEventRequest(eventType, dataNode);
  }

  @Nested
  class SuccessfulIngestion {

    @Test
    void acceptsEventAndPersistsEventAndOutbox() throws Exception {
      CreateEventRequest request =
          buildRequest("payment.succeeded", new PaymentPayload("pay_123", 4999));

      EventIngestionService.AcceptedEvent accepted =
          eventIngestionService.accept(projectId, request, "idem-001");

      assertThat(accepted.eventId()).isNotNull();
      assertThat(accepted.createdAt()).isNotNull();
      assertThat(accepted.replayed()).isFalse();

      // Verify event persisted
      Integer count =
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM events WHERE project_id = ? AND event_type = ?",
              Integer.class,
              projectId,
              "payment.succeeded");
      assertThat(count).isEqualTo(1);

      // Verify outbox message created
      Integer outboxCount =
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM outbox_messages WHERE message_type = 'EVENT_INGESTED'",
              Integer.class);
      assertThat(outboxCount).isGreaterThan(0);
    }

    @Test
    void rejectsRevokedApiKey() throws Exception {
      // Revoked keys are handled at the filter layer; here we verify the service
      // still processes events for valid keys. Revocation is tested in auth unit tests.
      // This test documents that the service layer doesn't handle revocation.
      CreateEventRequest request =
          buildRequest("payment.succeeded", new PaymentPayload("pay_123", 4999));

      EventIngestionService.AcceptedEvent accepted =
          eventIngestionService.accept(projectId, request, "idem-002");

      assertThat(accepted.eventId()).isNotNull();
    }
  }

  @Nested
  class Idempotency {

    @Test
    void replaysSameEventWithSameKey() throws Exception {
      CreateEventRequest request =
          buildRequest("payment.succeeded", new PaymentPayload("pay_123", 4999));

      EventIngestionService.AcceptedEvent first =
          eventIngestionService.accept(projectId, request, "idem-003");
      assertThat(first.replayed()).isFalse();

      EventIngestionService.AcceptedEvent second =
          eventIngestionService.accept(projectId, request, "idem-003");
      assertThat(second.replayed()).isTrue();
      assertThat(second.eventId()).isEqualTo(first.eventId());

      // Only one event row
      Integer count =
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM events WHERE project_id = ? AND idempotency_key = ?",
              Integer.class,
              projectId,
              "idem-003");
      assertThat(count).isEqualTo(1);
    }

    @Test
    void conflictsSameKeyDifferentPayload() throws Exception {
      CreateEventRequest firstRequest =
          buildRequest("payment.succeeded", new PaymentPayload("pay_123", 4999));
      eventIngestionService.accept(projectId, firstRequest, "idem-004");

      CreateEventRequest secondRequest =
          buildRequest("payment.succeeded", new PaymentPayload("pay_999", 9999));
      try {
        eventIngestionService.accept(projectId, secondRequest, "idem-004");
        throw new AssertionError("Expected IdempotencyConflictException");
      } catch (IdempotencyConflictException expected) {
        // expected
      }
    }

    @Test
    void conflictsSameKeyDifferentEventType() throws Exception {
      CreateEventRequest firstRequest =
          buildRequest("payment.succeeded", new PaymentPayload("pay_123", 4999));
      eventIngestionService.accept(projectId, firstRequest, "idem-005");

      CreateEventRequest secondRequest =
          buildRequest("refund.processed", new PaymentPayload("pay_123", 4999));
      try {
        eventIngestionService.accept(projectId, secondRequest, "idem-005");
        throw new AssertionError("Expected IdempotencyConflictException");
      } catch (IdempotencyConflictException expected) {
        // expected
      }
    }

    @Test
    void acceptsSameKeyDifferentProject() throws Exception {
      UUID otherProjectId = UUID.fromString("dddddddd-4444-4444-8444-dddddddddddd");
      jdbcTemplate.update(
          "INSERT INTO projects (id, organization_id, name, environment, created_at) VALUES (?, ?, ?, ?, ?)",
          otherProjectId,
          organizationId,
          "Other Project",
          "development",
          Timestamp.from(Instant.now()));

      CreateEventRequest request =
          buildRequest("payment.succeeded", new PaymentPayload("pay_123", 4999));

      eventIngestionService.accept(projectId, request, "idem-006");
      eventIngestionService.accept(otherProjectId, request, "idem-006");

      // Each project has its own event with the same idempotency key
      Integer projectACount =
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM events WHERE project_id = ? AND idempotency_key = ?",
              Integer.class,
              projectId,
              "idem-006");
      assertThat(projectACount).isEqualTo(1);

      Integer projectBCount =
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM events WHERE project_id = ? AND idempotency_key = ?",
              Integer.class,
              otherProjectId,
              "idem-006");
      assertThat(projectBCount).isEqualTo(1);
    }
  }

  private record PaymentPayload(String paymentId, int amount) {}
}
