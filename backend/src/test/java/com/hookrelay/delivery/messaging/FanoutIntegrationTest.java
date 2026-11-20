package com.hookrelay.delivery.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.hookrelay.delivery.application.EventFanoutService;
import com.hookrelay.delivery.application.FanoutProperties;
import com.hookrelay.delivery.application.RejectedNotification;
import com.hookrelay.delivery.repository.FanoutStore;
import com.hookrelay.event.api.CreateEventRequest;
import com.hookrelay.event.application.EventIngestionService;
import com.hookrelay.outbox.OutboxPublisher;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class FanoutIntegrationTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.7-alpine");
  @Container static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.4.0-management");
  private static final String QUEUE = "hookrelay.events.ingested.v1";
  private static final String DLQ = "hookrelay.events.ingested.dlq";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("test.context.marker", () -> "fanout-integration");
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("spring.rabbitmq.host", rabbit::getHost);
    r.add("spring.rabbitmq.port", rabbit::getAmqpPort);
    r.add("spring.rabbitmq.username", () -> "guest");
    r.add("spring.rabbitmq.password", () -> "guest");
    r.add("hookrelay.outbox.scheduling-enabled", () -> "false");
    r.add("hookrelay.fanout.enabled", () -> "false");
    r.add("hookrelay.fanout.accept-events-from", () -> "2000-01-01T00:00:00Z");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource source;
  @Autowired ObjectMapper mapper;
  @Autowired EventIngestionService ingestion;
  @Autowired EventFanoutService service;
  @Autowired FanoutStore fanouts;
  @Autowired EventNotificationParser parser;
  @Autowired ConnectionFactory connections;
  @Autowired RabbitTemplate rabbitTemplate;
  @Autowired OutboxPublisher publisher;
  @Autowired FanoutProperties properties;
  private RabbitAdmin admin;
  private FanoutConsumer consumer;
  private SimpleMeterRegistry metrics;
  private UUID project;
  private UUID foreignProject;

  @BeforeEach
  void setup() {
    admin = new RabbitAdmin(connections);
    admin.initialize();
    admin.purgeQueue(QUEUE);
    admin.purgeQueue(DLQ);
    metrics = new SimpleMeterRegistry();
    UUID org = UUID.randomUUID();
    project = UUID.randomUUID();
    foreignProject = UUID.randomUUID();
    jdbc.update("INSERT INTO organizations(id,name) VALUES (?,'fanout-test')", org);
    for (UUID id : List.of(project, foreignProject))
      jdbc.update(
          "INSERT INTO projects(id,organization_id,name,environment) VALUES (?,?,?,'development')",
          id,
          org,
          id.toString());
  }

  @AfterEach
  void cleanup() {
    if (consumer != null) {
      consumer.stop();
      consumer.destroy();
    }
    admin.purgeQueue(QUEUE);
    admin.purgeQueue(DLQ);
    metrics.close();
    for (String table :
        List.of(
            "deliveries",
            "event_fanouts",
            "outbox_messages",
            "events",
            "endpoint_dispatch_controls",
            "project_dispatch_controls",
            "endpoint_subscriptions",
            "endpoint_audit_events",
            "endpoints",
            "api_keys",
            "projects",
            "organizations")) jdbc.execute("DELETE FROM " + table);
  }

  private UUID endpoint(
      UUID owner, String eventType, boolean enabled, boolean deletedSubscription) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO endpoints(id,project_id,url,signing_secret_ciphertext,signing_secret_nonce,signing_secret_key_version,enabled)
        VALUES (?,?,?,'synthetic',?,'v1',?)
        """,
        id,
        owner,
        "https://8.8.8.8/" + id,
        new byte[12],
        enabled);
    jdbc.update(
        """
        INSERT INTO endpoint_subscriptions(id,endpoint_id,event_type,deleted_at) VALUES (?,?,?,?)
        """,
        UUID.randomUUID(),
        id,
        eventType,
        deletedSubscription ? Timestamp.from(Instant.now()) : null);
    return id;
  }

  private UUID event() {
    return ingestion
        .accept(
            project,
            new CreateEventRequest("test.created", mapper.readTree("{\"trusted\":true}")),
            null)
        .eventId();
  }

  private Message notification(UUID id) {
    return jdbc.queryForObject(
        "SELECT id,payload::text FROM outbox_messages WHERE project_id=? AND aggregate_id=?",
        (rs, row) -> {
          var props = new MessageProperties();
          props.setContentType("application/json");
          props.setMessageId(rs.getString("id"));
          return new Message(rs.getString("payload").getBytes(StandardCharsets.UTF_8), props);
        },
        project,
        id);
  }

  private void start() {
    consumer = new FanoutConsumer(connections, parser, service, properties, metrics, QUEUE);
    consumer.start();
  }

  private void send(Message message) {
    rabbitTemplate.send("hookrelay.events", "events.ingested", message);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  private double outcomes(String outcome) {
    var counter = metrics.find("hookrelay.fanout.messages").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  private void processed(int number) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() ->
            assertThat(outcomes("created") + outcomes("duplicate") + outcomes("rejected"))
                .isEqualTo(number));
  }

  @Test
  void editsAfterSelectionDoNotChangeTheSnapshotEvenBeforeCommit() throws Exception {
    UUID event = event();
    UUID selected = endpoint(project, "test.created", true, false);
    var notification = parser.parse(notification(event));
    jdbc.execute(
        """
        CREATE FUNCTION test_pause_fanout() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          PERFORM set_config('lock_timeout','0',true);
          PERFORM pg_advisory_xact_lock(7182);
          RETURN NEW;
        END $$
        """);
    jdbc.execute(
        "CREATE TRIGGER test_pause BEFORE INSERT ON deliveries FOR EACH ROW EXECUTE FUNCTION test_pause_fanout()");
    try (Connection blocker = source.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      try (var lock = blocker.createStatement()) {
        lock.execute("SELECT pg_advisory_lock(7182)");
      }
      try {
        var result = executor.submit(() -> service.process(notification));
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=7182 AND NOT granted",
                            Integer.class))
                    .isEqualTo(1));
        jdbc.update(
            "UPDATE endpoints SET enabled=false,url='https://8.8.4.4/changed' WHERE project_id=? AND id=?",
            project,
            selected);
        jdbc.update(
            "UPDATE endpoint_subscriptions SET deleted_at=clock_timestamp() WHERE endpoint_id=?",
            selected);
        endpoint(project, "test.created", true, false);
        try (var lock = blocker.createStatement()) {
          lock.execute("SELECT pg_advisory_unlock(7182)");
        }
        assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(EventFanoutService.Result.CREATED);
        assertThat(
                jdbc.queryForObject(
                    "SELECT endpoint_id FROM deliveries WHERE project_id=? AND event_id=?",
                    UUID.class,
                    project,
                    event))
            .isEqualTo(selected);
        assertThat(
                jdbc.queryForObject(
                    "SELECT destination_url FROM deliveries WHERE project_id=? AND event_id=?",
                    String.class,
                    project,
                    event))
            .isEqualTo("https://8.8.8.8/" + selected);
      } finally {
        try (var lock = blocker.createStatement()) {
          lock.execute("SELECT pg_advisory_unlock_all()");
        }
      }
    } finally {
      jdbc.execute("DROP TRIGGER test_pause ON deliveries");
      jdbc.execute("DROP FUNCTION test_pause_fanout()");
    }
  }

  @Test
  void reconciliationReportsOnlyOldPublishedMissingSnapshotsInTheProject() {
    UUID event = event();
    assertThat(fanouts.findPublishedWithoutSnapshot(project, 10)).isEmpty();
    jdbc.update(
        "UPDATE outbox_messages SET status='PUBLISHED',published_at=clock_timestamp()-interval '2 minutes' WHERE project_id=? AND aggregate_id=?",
        project,
        event);
    assertThat(fanouts.findPublishedWithoutSnapshot(project, 10)).containsExactly(event);
    assertThat(fanouts.findPublishedWithoutSnapshot(foreignProject, 10)).isEmpty();
    service.process(parser.parse(notification(event)));
    assertThat(fanouts.findPublishedWithoutSnapshot(project, 10)).isEmpty();
    assertThatThrownBy(() -> fanouts.findPublishedWithoutSnapshot(project, 101))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void selectsCurrentSubscriptionsAndFreezesDatabasePayloadAndDestination() {
    UUID event = event(); // Subscriptions added after ingestion still qualify.
    UUID eligible = endpoint(project, "test.created", true, false);
    endpoint(project, "test.created", false, false);
    endpoint(project, "test.created", true, true);
    endpoint(project, "test.other", true, false);
    endpoint(foreignProject, "test.created", true, false);
    UUID deleted = endpoint(project, "test.created", true, false);
    jdbc.update(
        "UPDATE endpoints SET deleted_at=clock_timestamp() WHERE project_id=? AND id=?",
        project,
        deleted);
    Message message = notification(event);
    var altered = (ObjectNode) mapper.readTree(message.getBody());
    altered.set("data", mapper.readTree("{\"untrusted\":true}"));
    service.process(
        parser.parse(
            new Message(mapper.writeValueAsBytes(altered), message.getMessageProperties())));
    assertThat(count("deliveries")).isEqualTo(1);
    assertThat(count("delivery_cycles")).isEqualTo(1);
    var snapshot = fanouts.find(project, event).orElseThrow();
    var body = mapper.readTree(snapshot.body());
    assertThat(body.path("data").path("trusted").asBoolean()).isTrue();
    assertThat(body.has("projectId")).isFalse();
    assertThat(snapshot.recipientCount()).isEqualTo(1);
    endpoint(project, "test.created", true, false);
    jdbc.update(
        "UPDATE endpoints SET url='https://8.8.4.4/new',enabled=false WHERE project_id=? AND id=?",
        project,
        eligible);
    assertThat(service.process(parser.parse(message)))
        .isEqualTo(EventFanoutService.Result.DUPLICATE);
    assertThat(count("deliveries")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT destination_url FROM deliveries WHERE project_id=? AND event_id=?",
                String.class,
                project,
                event))
        .isEqualTo("https://8.8.8.8/" + eligible);
    assertThat(fanouts.find(project, event).orElseThrow().body()).isEqualTo(snapshot.body());
  }

  @Test
  void zeroRecipientCommitRemainsFinalOnBrokerRedelivery() {
    UUID event = event();
    Message message = notification(event);
    start();
    send(message);
    processed(1);
    endpoint(project, "test.created", true, false);
    send(message);
    processed(2);
    assertThat(fanouts.find(project, event).orElseThrow().recipientCount()).isZero();
    assertThat(count("deliveries")).isZero();
    assertThat(outcomes("duplicate")).isEqualTo(1);
  }

  @Test
  void publisherAndConcurrentConsumersMaterializeOnlyOneInitialDelivery() {
    UUID event = event();
    endpoint(project, "test.created", true, false);
    Message message = notification(event);
    start();
    publisher.publishPendingMessages();
    for (int i = 0; i < 9; i++) send(message);
    processed(10);
    assertThat(count("deliveries")).isEqualTo(1);
    assertThat(count("delivery_cycles")).isEqualTo(1);
    assertThat(outcomes("created")).isEqualTo(1);
    assertThat(outcomes("duplicate")).isEqualTo(9);
  }

  @Test
  void simultaneousTransactionsSerializeOnEventLock() throws Exception {
    UUID event = event();
    endpoint(project, "test.created", true, false);
    var input = parser.parse(notification(event));
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Future<EventFanoutService.Result>>();
      for (int i = 0; i < 4; i++)
        tasks.add(
            executor.submit(() -> {
              start.await();
              return service.process(input);
            }));
      start.countDown();
      int created = 0;
      for (var task : tasks)
        if (task.get(20, TimeUnit.SECONDS) == EventFanoutService.Result.CREATED) created++;
      assertThat(created).isEqualTo(1);
      assertThat(count("deliveries")).isEqualTo(1);
    }
  }

  @Test
  void committedFanoutSurvivesChannelCrashBeforeAcknowledgement() throws Exception {
    UUID event = event();
    endpoint(project, "test.created", true, false);
    Message message = notification(event);
    send(message);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isEqualTo(1));
    var client = new com.rabbitmq.client.ConnectionFactory();
    client.setHost(rabbit.getHost());
    client.setPort(rabbit.getAmqpPort());
    var connection = client.newConnection();
    Channel channel = connection.createChannel();
    var received = channel.basicGet(QUEUE, false);
    assertThat(received).isNotNull();
    service.process(parser.parse(message)); // durable DB commit; intentionally no broker ACK
    channel.close();
    connection.close();
    start();
    processed(1);
    assertThat(outcomes("duplicate")).isEqualTo(1);
    assertThat(count("deliveries")).isEqualTo(1);
  }

  @Test
  void acknowledgementObservesCommitOnIndependentConnection() throws Exception {
    UUID event = event();
    endpoint(project, "test.created", true, false);
    Message message = notification(event);
    send(message);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isEqualTo(1));
    var client = new com.rabbitmq.client.ConnectionFactory();
    client.setHost(rabbit.getHost());
    client.setPort(rabbit.getAmqpPort());
    var connection = client.newConnection();
    Channel channel = connection.createChannel();
    try {
      var received = channel.basicGet(QUEUE, false);
      assertThat(received).isNotNull();
      message.getMessageProperties().setDeliveryTag(received.getEnvelope().getDeliveryTag());
      var acknowledged = new AtomicBoolean();
      Channel observed =
          (Channel)
              java.lang.reflect.Proxy.newProxyInstance(
                  Channel.class.getClassLoader(),
                  new Class<?>[] {Channel.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("basicAck")) {
                      assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                          .isFalse();
                      try (Connection independent = source.getConnection();
                          var query =
                              independent.prepareStatement(
                                  "SELECT count(*) FROM event_fanouts WHERE project_id=? AND event_id=?")) {
                        query.setObject(1, project);
                        query.setObject(2, event);
                        try (var result = query.executeQuery()) {
                          result.next();
                          assertThat(result.getInt(1)).isEqualTo(1);
                        }
                      }
                      assertThat(args[1]).isEqualTo(false);
                      acknowledged.set(true);
                    }
                    try {
                      return method.invoke(channel, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                      throw e.getCause();
                    }
                  });
      start();
      consumer.onMessage(message, observed);
      assertThat(acknowledged).isTrue();
      assertThat(outcomes("created")).isEqualTo(1);
    } finally {
      channel.close();
      connection.close();
    }
  }

  @Test
  void databaseLockFailurePausesAndRedeliversInsteadOfPoisoningMessage() throws Exception {
    UUID event = event();
    endpoint(project, "test.created", true, false);
    Message message = notification(event);
    try (Connection blocking = source.getConnection()) {
      blocking.setAutoCommit(false);
      try (var lock =
          blocking.prepareStatement(
              "SELECT id FROM events WHERE project_id=? AND id=? FOR UPDATE")) {
        lock.setObject(1, project);
        lock.setObject(2, event);
        lock.executeQuery().close();
      }
      start();
      send(message);
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(outcomes("transient_failure")).isEqualTo(1));
      assertThat(count("event_fanouts")).isZero();
      assertThat(admin.getQueueInfo(DLQ).getMessageCount()).isZero();
      blocking.commit();
    }
    processed(1);
    assertThat(count("deliveries")).isEqualTo(1);
  }

  @Test
  void malformedOversizedForeignAndLegacyNotificationsGoToInternalDlq() {
    UUID event = event();
    Message valid = notification(event);
    var foreign = (ObjectNode) mapper.readTree(valid.getBody());
    foreign.put("projectId", foreignProject.toString());
    var mismatch = (ObjectNode) mapper.readTree(valid.getBody());
    mismatch.put("eventType", "test.wrong");
    start();
    send(new Message("not json".getBytes(StandardCharsets.UTF_8), valid.getMessageProperties()));
    send(new Message(new byte[1024 * 1024 + 1], valid.getMessageProperties()));
    send(new Message(mapper.writeValueAsBytes(foreign), valid.getMessageProperties()));
    send(new Message(mapper.writeValueAsBytes(mismatch), valid.getMessageProperties()));
    send(
        new Message(
            "{\"endpointId\":\"legacy\"}".getBytes(StandardCharsets.UTF_8),
            valid.getMessageProperties()));
    processed(5);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(admin.getQueueInfo(DLQ).getMessageCount()).isEqualTo(5));
    assertThat(count("event_fanouts")).isZero();
    assertThat(count("deliveries")).isZero();
  }

  @Test
  void failedMaterializationRollsBackDeliveriesCyclesAndMarker() {
    UUID event = event();
    endpoint(project, "test.created", true, false);
    // Simulate a database invariant failure after the set-based delivery/cycle INSERT.
    jdbc.execute(
        "ALTER TABLE event_fanouts ADD CONSTRAINT test_reject_marker CHECK (recipient_count<0)");
    try {
      assertThatThrownBy(() -> service.process(parser.parse(notification(event))))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(count("deliveries")).isZero();
      assertThat(count("delivery_cycles")).isZero();
      assertThat(count("event_fanouts")).isZero();
    } finally {
      jdbc.execute("ALTER TABLE event_fanouts DROP CONSTRAINT test_reject_marker");
    }
    service.process(parser.parse(notification(event)));
    assertThat(count("deliveries")).isEqualTo(1);
  }

  @Test
  void refusesOversizedLegacyProjectWithoutPartialFanout() {
    UUID event = event();
    jdbc.update(
        """
        WITH e AS (INSERT INTO endpoints(id,project_id,url,signing_secret_ciphertext,signing_secret_nonce,signing_secret_key_version)
          SELECT gen_random_uuid(),?,'https://8.8.8.8/legacy/' || n,'synthetic',?,'v1' FROM generate_series(1,1001) n RETURNING id)
        INSERT INTO endpoint_subscriptions(id,endpoint_id,event_type) SELECT gen_random_uuid(),id,'test.created' FROM e
        """,
        project,
        new byte[12]);
    assertThatThrownBy(() -> service.process(parser.parse(notification(event))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("deliveries")).isZero();
    assertThat(count("delivery_cycles")).isZero();
    assertThat(count("event_fanouts")).isZero();
  }

  @Test
  void rejectsEventsBeforeReviewedCutoffWithoutCreatingSuppressionMarkers() {
    UUID event = event();
    Message message = notification(event);
    jdbc.update(
        "UPDATE events SET created_at='1999-01-01T00:00:00Z' WHERE project_id=? AND id=?",
        project,
        event);
    var old = (ObjectNode) mapper.readTree(message.getBody());
    old.put("createdAt", "1999-01-01T00:00:00Z");
    assertThatThrownBy(() ->
            service.process(
                parser.parse(
                    new Message(mapper.writeValueAsBytes(old), message.getMessageProperties()))))
        .isInstanceOf(RejectedNotification.class)
        .hasMessage("HISTORICAL_EVENT");
    assertThat(count("event_fanouts")).isZero();
  }
}
