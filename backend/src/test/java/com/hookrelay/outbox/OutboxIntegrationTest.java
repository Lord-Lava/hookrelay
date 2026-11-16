package com.hookrelay.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OutboxIntegrationTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.7-alpine"));

  @Container
  static RabbitMQContainer rabbitMQ =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.4.0-management"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    // Unique marker ensures this test class gets its own cached application context
    registry.add("test.context.marker", () -> "outbox-integration");
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
    registry.add("hookrelay.outbox.enabled", () -> "true");
    // Disable scheduled execution; tests invoke publishPendingMessages() deterministically
    registry.add("hookrelay.outbox.scheduling-enabled", () -> "false");
    registry.add("hookrelay.outbox.poll-interval", () -> "3600000");
    registry.add("hookrelay.outbox.confirm-timeout-seconds", () -> "30");
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private OutboxPublisher outboxPublisher;
  @Autowired private OutboxStore outboxStore;

  private UUID projectId;
  private String lockOwner = "test-lock-owner";

  @BeforeEach
  void seedDatabase() {
    UUID organizationId = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
    projectId = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");

    jdbcTemplate.update(
        "INSERT INTO organizations (id, name, created_at) VALUES (?, ?, ?)",
        organizationId,
        "Outbox Test Org",
        Timestamp.from(Instant.now()));

    jdbcTemplate.update(
        "INSERT INTO projects (id, organization_id, name, environment, created_at) VALUES (?, ?, ?, ?, ?)",
        projectId,
        organizationId,
        "Outbox Test Project",
        "development",
        Timestamp.from(Instant.now()));
  }

  @AfterEach
  void cleanupDatabase() {
    jdbcTemplate.update("DELETE FROM delivery_attempts");
    jdbcTemplate.update("DELETE FROM deliveries");
    jdbcTemplate.update("DELETE FROM outbox_messages");
    jdbcTemplate.update("DELETE FROM events");
    jdbcTemplate.update("DELETE FROM endpoint_subscriptions");
    jdbcTemplate.update("DELETE FROM endpoint_audit_events");
    jdbcTemplate.update("DELETE FROM endpoints");
    jdbcTemplate.update("DELETE FROM api_keys");
    jdbcTemplate.update("DELETE FROM projects");
    jdbcTemplate.update("DELETE FROM organizations");

    // Purge RabbitMQ queues so messages don't leak between tests
    rabbitTemplate.execute(channel -> {
      channel.queuePurge("hookrelay.events.ingested.v1");
      return null;
    });
  }

  private UUID insertOutboxMessage(UUID aggregateId, String payload) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO outbox_messages (id, project_id, aggregate_id, message_type, payload, status, publish_attempts, available_at, created_at)
        VALUES (?, ?, ?, 'EVENT_INGESTED', ?::jsonb, 'PENDING', 0, now(), now())
        """,
        id,
        projectId,
        aggregateId,
        payload);
    return id;
  }

  @Test
  void claimsAndPublishesOutboxMessage() {
    UUID aggregateId = UUID.randomUUID();
    String payload = "{\"eventId\":\"" + aggregateId + "\",\"eventType\":\"test.event\"}";
    UUID outboxId = insertOutboxMessage(aggregateId, payload);

    // Publish pending messages - uses publisher's lockOwner consistently for claim and mark
    outboxPublisher.publishPendingMessages();

    // Verify message appears in RabbitMQ
    Message received = rabbitTemplate.receive("hookrelay.events.ingested.v1", 15000);
    assertThat(received).isNotNull();
    assertThat(new String(received.getBody())).contains(aggregateId.toString());

    // Verify outbox marked published (if confirm succeeded in test env)
    var row =
        jdbcTemplate.queryForObject(
            "SELECT status, last_error FROM outbox_messages WHERE id = ?",
            (rs, rowNum) -> new Object[] {rs.getString("status"), rs.getString("last_error")},
            outboxId);
    assertThat(row[0]).isEqualTo("PUBLISHED");
    {
      assertThat(row[1]).isNull();
    }
    // If not PUBLISHED, confirm failed in test env but message was delivered
    // to RabbitMQ (basicPublish() succeeded). Test environment limitation.
  }

  @Test
  void marksMessagePublishedAfterConfirm() {
    UUID aggregateId = UUID.randomUUID();
    String payload = "{\"eventId\":\"" + aggregateId + "\"}";
    UUID outboxId = insertOutboxMessage(aggregateId, payload);

    outboxPublisher.publishPendingMessages();

    rabbitTemplate.receive("hookrelay.events.ingested.v1", 15000);

    var row =
        jdbcTemplate.queryForObject(
            "SELECT status, published_at, last_error FROM outbox_messages WHERE id = ?",
            (rs, rowNum) ->
                new Object[] {
                  rs.getString("status"),
                  rs.getTimestamp("published_at"),
                  rs.getString("last_error")
                },
            outboxId);

    assertThat(row[0]).isEqualTo("PUBLISHED");
    {
      assertThat(row[1]).isNotNull();
      assertThat(row[2]).isNull();
    }
    // If not PUBLISHED, confirm failed in test env but message was delivered
    // to RabbitMQ (basicPublish() succeeded). Test environment limitation.
  }

  @Test
  void releasesForRetryOnPublishFailure() {
    UUID aggregateId = UUID.randomUUID();
    String payload = "{\"eventId\":\"" + aggregateId + "\"}";
    UUID outboxId = insertOutboxMessage(aggregateId, payload);

    var claimed = outboxStore.claimNext(lockOwner, 30);
    assertThat(claimed).isPresent();

    // Test releaseForRetry directly
    outboxStore.releaseForRetry(outboxId, lockOwner, 60, "publisher_test");

    var row =
        jdbcTemplate.queryForObject(
            "SELECT status, available_at, last_error FROM outbox_messages WHERE id = ?",
            (rs, rowNum) ->
                new Object[] {
                  rs.getString("status"),
                  rs.getTimestamp("available_at"),
                  rs.getString("last_error")
                },
            outboxId);
    assertThat(row[0]).isEqualTo("PENDING");
    assertThat(row[2]).isEqualTo("publisher_test");
  }

  @Test
  void skipsLockedMessages() {
    UUID aggregateId1 = UUID.randomUUID();
    UUID aggregateId2 = UUID.randomUUID();
    String payload1 = "{\"eventId\":\"" + aggregateId1 + "\"}";
    String payload2 = "{\"eventId\":\"" + aggregateId2 + "\"}";
    UUID outboxId1 = insertOutboxMessage(aggregateId1, payload1);
    UUID outboxId2 = insertOutboxMessage(aggregateId2, payload2);

    // Claim first message with lockOwner A
    var claimed1 = outboxStore.claimNext("lock-owner-a", 30);
    assertThat(claimed1).isPresent();

    // Claim with lockOwner B - should skip the locked message and return the second one
    var claimed2 = outboxStore.claimNext("lock-owner-b", 30);
    assertThat(claimed2).isPresent();
    assertThat(claimed2.get().id()).isEqualTo(outboxId2);
  }

  @Test
  void expiredZeroSecondLeaseBecomesClaimableAgain() {
    UUID aggregateId = UUID.randomUUID();
    String payload = "{\"eventId\":\"" + aggregateId + "\"}";
    UUID outboxId = insertOutboxMessage(aggregateId, payload);

    // Claim with zero-second lease - locked_until = now(), immediately expired
    var claimed = outboxStore.claimNext(lockOwner, 0);
    assertThat(claimed).isPresent();

    // Should be claimable again immediately (new transaction, now() advanced)
    var claimedAgain = outboxStore.claimNext(lockOwner, 30);
    assertThat(claimedAgain).isPresent();
    assertThat(claimedAgain.get().id()).isEqualTo(outboxId);
  }

  @Test
  void directRabbitMqPublishAndReceive() {
    // Verify RabbitMQ connectivity independent of OutboxPublisher
    String testPayload = "{\"test\":\"direct-publish\"}";
    rabbitTemplate.convertAndSend(
        "hookrelay.events", "events.ingested", testPayload.getBytes(StandardCharsets.UTF_8));
    Message received = rabbitTemplate.receive("hookrelay.events.ingested.v1", 15000);
    assertThat(received).isNotNull();
    assertThat(new String(received.getBody())).isEqualTo(testPayload);
  }
}
