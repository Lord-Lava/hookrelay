package com.hookrelay.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hookrelay.delivery.domain.AttemptOutcome;
import com.hookrelay.delivery.domain.Delivery;
import com.hookrelay.delivery.domain.DeliveryStatus;
import com.hookrelay.delivery.domain.FanoutSnapshot;
import com.hookrelay.delivery.repository.DeliveryStore;
import com.hookrelay.delivery.repository.FanoutStore;
import com.hookrelay.delivery.signing.WebhookSigner;
import com.hookrelay.delivery.transport.TransportResult;
import com.hookrelay.delivery.transport.WebhookTransport;
import com.hookrelay.endpoint.application.EndpointService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class DeliveryWorkerIntegrationTest {

  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.7-alpine");

  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private DeliveryStore deliveries;
  private final RetryScheduler retryScheduler = new RetryScheduler(10, 3600, Duration.ofHours(24));
  private String schema;

  private final FanoutStore fanoutStore = mock(FanoutStore.class);
  private final EndpointService endpointService = mock(EndpointService.class);
  private final WebhookSigner signer = mock(WebhookSigner.class);
  private final WebhookTransport transport = mock(WebhookTransport.class);

  private UUID project;
  private UUID endpoint;
  private UUID event;

  @BeforeEach
  void setUp() {
    reset(fanoutStore, endpointService, signer, transport);
    schema = "worker_" + UUID.randomUUID().toString().replace("-", "");
    source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema="
                + schema,
            postgres.getUsername(),
            postgres.getPassword());
    jdbc = new JdbcTemplate(source);
    jdbc.execute("CREATE SCHEMA " + schema);
    migrate("11");
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    deliveries = new DeliveryStore(jdbc, tx, retryScheduler);
    project = UUID.randomUUID();
    endpoint = UUID.randomUUID();
    event = UUID.randomUUID();
    UUID org = UUID.randomUUID();
    try {
      jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'test')", org);
    } catch (Exception e) {
      StringBuilder sb = new StringBuilder("INSERT organizations failed: ").append(e.getMessage());
      Throwable c = e.getCause();
      while (c != null) {
        sb.append(" | caused by: ").append(c.getMessage());
        c = c.getCause();
      }
      throw new IllegalStateException(sb.toString(), e);
    }
    jdbc.update(
        "INSERT INTO projects(id,organization_id,name,environment) VALUES (?,?,?,'development')",
        project,
        org,
        project);
    jdbc.update(
        """
        INSERT INTO endpoints(id,project_id,url,signing_secret_ciphertext,signing_secret_nonce,signing_secret_key_version,enabled)
        VALUES (?,?,'https://8.8.8.8/hook','synthetic-ciphertext',?,'v1',true)
        """,
        endpoint,
        project,
        new byte[12]);
    jdbc.update(
        """
        INSERT INTO events(id,project_id,event_type,payload,request_fingerprint)
        VALUES (?,?,'test.created','{}',?)
        """,
        event,
        project,
        "a".repeat(64));
  }

  @AfterEach
  void tearDown() {
    if (jdbc != null) {
      String currentSchema = jdbc.queryForObject("SELECT current_schema()", String.class);
      jdbc.execute("DROP SCHEMA IF EXISTS " + currentSchema + " CASCADE");
    }
  }

  private void migrate(String target) {
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
        .migrate();
  }

  @Test
  void givenHttp500ThenHttp200_whenProcess_thenRetriesAndDelivers() throws Exception {
    Delivery d = createDeliveryAndClaim();
    whenFanout(d.eventId());
    whenSecret();
    whenSigner(d.id());

    DeliveryWorker worker = newWorker();
    Instant retryAt = Instant.now().plusSeconds(60);
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenReturn(
            new TransportResult(
                500,
                null,
                Instant.now(),
                AttemptOutcome.RETRYABLE_FAILURE,
                50L,
                "HTTP_5XX",
                retryAt));
    DeliveryStatus first = worker.process(project, d.id(), d.leaseToken());
    assertThat(first).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);

    // Simulate time passing so the scheduled retry is due; then re-claim.
    jdbc.update(
        "UPDATE deliveries SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE project_id=? AND id=?",
        project,
        d.id());
    Delivery d2 =
        deliveries
            .claim(project, d.id(), "integration-worker", Duration.ofSeconds(60))
            .orElseThrow();
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenReturn(
            new TransportResult(
                200, null, Instant.now(), AttemptOutcome.SUCCEEDED, 40L, null, null));
    DeliveryStatus second = worker.process(project, d2.id(), d2.leaseToken());
    assertThat(second).isEqualTo(DeliveryStatus.DELIVERED);

    assertThat(deliveries.find(project, d.id()).orElseThrow().status())
        .isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(deliveries.attempts(project, d.id(), 100, 0)).hasSize(2);
    assertThat(deliveries.attempts(project, d.id(), 100, 0).get(0).outcome())
        .isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
    assertThat(deliveries.attempts(project, d.id(), 100, 0).get(1).outcome())
        .isEqualTo(AttemptOutcome.SUCCEEDED);
  }

  @Test
  void givenHttp429ThenHttp200_whenProcess_thenRetriesAndDelivers() throws Exception {
    Delivery d = createDeliveryAndClaim();
    whenFanout(d.eventId());
    whenSecret();
    whenSigner(d.id());

    DeliveryWorker worker = newWorker();
    Instant retryAfter = Instant.now().plusSeconds(60);
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenReturn(
            new TransportResult(
                429,
                null,
                Instant.now(),
                AttemptOutcome.RETRYABLE_FAILURE,
                30L,
                "HTTP_429",
                retryAfter));
    DeliveryStatus first = worker.process(project, d.id(), d.leaseToken());
    assertThat(first).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);

    // Simulate time passing so the scheduled retry is due; then re-claim.
    jdbc.update(
        "UPDATE deliveries SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE project_id=? AND id=?",
        project,
        d.id());
    Delivery d2 =
        deliveries
            .claim(project, d.id(), "integration-worker", Duration.ofSeconds(60))
            .orElseThrow();
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenReturn(
            new TransportResult(
                200, null, Instant.now(), AttemptOutcome.SUCCEEDED, 40L, null, null));
    DeliveryStatus second = worker.process(project, d2.id(), d2.leaseToken());
    assertThat(second).isEqualTo(DeliveryStatus.DELIVERED);

    assertThat(deliveries.find(project, d.id()).orElseThrow().status())
        .isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(deliveries.attempts(project, d.id(), 100, 0)).hasSize(2);
  }

  @Test
  void givenHttp400_whenProcess_thenDeadLettered() throws Exception {
    Delivery d = createDeliveryAndClaim();
    whenFanout(d.eventId());
    whenSecret();
    whenSigner(d.id());

    DeliveryWorker worker = newWorker();
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenReturn(
            new TransportResult(
                400, null, Instant.now(), AttemptOutcome.PERMANENT_FAILURE, 20L, "HTTP_4XX", null));
    DeliveryStatus result = worker.process(project, d.id(), d.leaseToken());
    assertThat(result).isEqualTo(DeliveryStatus.DEAD_LETTERED);

    var dead = deliveries.find(project, d.id()).orElseThrow();
    assertThat(dead.status()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
    assertThat(dead.lastErrorCode()).isEqualTo("HTTP_4XX");
    assertThat(dead.terminalAt()).isNotNull();
    assertThat(deliveries.attempts(project, d.id(), 100, 0)).hasSize(1);
    assertThat(deliveries.attempts(project, d.id(), 100, 0).getFirst().outcome())
        .isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
    assertThat(deliveries.attempts(project, d.id(), 100, 0).getFirst().httpStatus()).isEqualTo(400);
  }

  @Test
  void givenNetworkTimeout_whenProcess_thenRetryScheduled() throws Exception {
    Delivery d = createDeliveryAndClaim();
    whenFanout(d.eventId());
    whenSecret();
    whenSigner(d.id());

    DeliveryWorker worker = newWorker();
    IOException cause = new IOException("connect timed out");
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenThrow(new com.hookrelay.delivery.transport.TransportException("IO", cause));
    DeliveryStatus result = worker.process(project, d.id(), d.leaseToken());
    assertThat(result).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);

    var scheduled = deliveries.find(project, d.id()).orElseThrow();
    assertThat(scheduled.status()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    assertThat(scheduled.nextAttemptAt()).isNotNull();
    assertThat(deliveries.attempts(project, d.id(), 100, 0).getFirst().outcome())
        .isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
    verify(transport).send(any(), any(), any());
  }

  private Delivery createDeliveryAndClaim() {
    UUID deliveryId = UUID.randomUUID();
    Instant now = Instant.now();
    tx.executeWithoutResult(status -> {
      jdbc.update(
          """
          INSERT INTO deliveries(id,project_id,event_id,endpoint_id,destination_url,status,
            attempt_count,next_attempt_at,cycle_number,cycle_attempt_count,cycle_started_at,
            cycle_deadline,policy_version,max_attempts)
          VALUES (?,?,?,?,'https://8.8.8.8/hook','PENDING',0,?,0,0,?,?,'v1',8)
          """,
          deliveryId,
          project,
          event,
          endpoint,
          Timestamp.from(now),
          Timestamp.from(now),
          Timestamp.from(now.plus(Duration.ofHours(24))));
      jdbc.update(
          """
          INSERT INTO delivery_cycles(project_id,delivery_id,cycle_number,started_at,deadline,policy_version,max_attempts)
          VALUES (?,?,0,?,?,'v1',8)
          """,
          project,
          deliveryId,
          Timestamp.from(now),
          Timestamp.from(now.plus(Duration.ofHours(24))));
    });

    var claimed =
        deliveries.claim(project, deliveryId, "integration-worker", Duration.ofSeconds(60));
    assertThat(claimed).isPresent();
    return claimed.orElseThrow();
  }

  private void whenFanout(UUID eventId) {
    when(fanoutStore.find(project, eventId))
        .thenReturn(
            Optional.of(
                new FanoutSnapshot(
                    project,
                    eventId,
                    Instant.now(),
                    1,
                    "v1",
                    "{}".getBytes(StandardCharsets.UTF_8))));
  }

  private void whenSecret() {
    when(endpointService.getSigningSecret(endpoint, project)).thenReturn("whsec_dGVzdA==");
  }

  private void whenSigner(UUID deliveryId) {
    when(signer.sign(eq(deliveryId), any(Long.class), any(byte[].class), any(String.class)))
        .thenReturn("v1,sig");
  }

  private DeliveryWorker newWorker() {
    return new DeliveryWorker(
        deliveries,
        fanoutStore,
        transport,
        signer,
        endpointService,
        retryScheduler,
        new DataSourceTransactionManager(source));
  }
}
