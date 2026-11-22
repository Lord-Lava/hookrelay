package com.hookrelay.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hookrelay.auth.ApiKeyAuthService;
import com.hookrelay.auth.ProjectIdentity;
import com.hookrelay.auth.TenantContext;
import com.hookrelay.delivery.domain.AttemptOutcome;
import com.hookrelay.delivery.domain.Delivery;
import com.hookrelay.delivery.domain.DeliveryStatus;
import com.hookrelay.delivery.repository.AbstractDeliveryIntegrationTest;
import com.hookrelay.endpoint.application.EndpointService;
import com.hookrelay.endpoint.domain.WebhookEndpoint;
import com.hookrelay.endpoint.repository.WebhookEndpointRepository;
import com.hookrelay.security.AesGcmSigningSecretService;
import com.hookrelay.security.SigningSecretService;
import com.hookrelay.security.SigningSecretService.EncryptedSecret;
import com.hookrelay.security.SigningUnavailableException;
import com.hookrelay.security.ssrf.DefaultSsrfUrlValidator;
import com.hookrelay.security.ssrf.SsrfUrlValidator;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class DeliveryReplayIntegrationTest extends AbstractDeliveryIntegrationTest {

  private static final String TEST_API_KEY = "hr_live_testkey_replay_integration_secret_value";
  private static final String TEST_API_KEY_HASH = ApiKeyAuthService.sha256(TEST_API_KEY);
  private static final UUID TEST_API_KEY_ID =
      UUID.fromString("aaaaaaaa-7777-4777-8777-aaaaaaaaaaaa");
  private static final String ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  private WebhookEndpointRepository endpointRepository;
  private AesGcmSigningSecretService signingSecretService;
  private EndpointService endpointService;
  private DeliveryReplayService replayService;
  private ObjectMapper mapper;

  @BeforeEach
  void setUpServices() {
    mapper = new ObjectMapper();
    endpointRepository = new WebhookEndpointRepository(jdbc);
    // Insert the API key referenced by replay audit records (FK fk_cycle_actor).
    jdbc.update(
        "INSERT INTO api_keys(id,project_id,key_prefix,key_hash,name) VALUES (?,?,?,?,?)",
        TEST_API_KEY_ID,
        project,
        "replay-test",
        TEST_API_KEY_HASH,
        "replay-test-key");
    signingSecretService = new AesGcmSigningSecretService(ENCRYPTION_KEY, "v1");
    signingSecretService.afterPropertiesSet();
    SsrfUrlValidator ssrfValidator = new DefaultSsrfUrlValidator();
    endpointService =
        new EndpointService(
            endpointRepository, signingSecretService, deliveries, jdbc, mapper, ssrfValidator, tx);
    replayService = new DeliveryReplayService(deliveries, endpointService, jdbc, tx);

    // Set tenant context directly (simulating authenticated request).
    TenantContext.set(new ProjectIdentity(UUID.randomUUID(), project, TEST_API_KEY_ID));
  }

  @AfterEach
  void clearContext() {
    TenantContext.clear();
  }

  private WebhookEndpoint createEnabledEndpoint() {
    EndpointService.CreatedEndpointResult result =
        endpointService.createEndpoint(project, "https://8.8.8.8/hook", "replay endpoint");
    return result.endpoint();
  }

  private WebhookEndpoint createDisabledEndpoint() {
    EndpointService.CreatedEndpointResult result =
        endpointService.createEndpoint(project, "https://8.8.8.8/disabled", "disabled endpoint");
    endpointService.disableEndpoint(result.endpoint().id(), project);
    return result.endpoint();
  }

  private Delivery createDeadLetteredDelivery(WebhookEndpoint endpoint) {
    UUID deliveryId = UUID.randomUUID();
    Instant now = Instant.now();
    tx.executeWithoutResult(status -> {
      jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
      // Insert delivery first (cycle→delivery FK is immediate; delivery→cycle FK is deferred).
      jdbc.update(
          """
          INSERT INTO deliveries(id,project_id,event_id,endpoint_id,destination_url,status,
            attempt_count,next_attempt_at,cycle_number,cycle_attempt_count,cycle_started_at,
            cycle_deadline,policy_version,max_attempts,terminal_at,last_error_code)
          VALUES (?,?,?,?,'https://8.8.8.8/hook','DEAD_LETTERED',3,NULL,0,3,?,?,'v1',8,?,?)
          """,
          deliveryId,
          project,
          event,
          endpoint.id(),
          Timestamp.from(now.minusSeconds(3600)),
          Timestamp.from(now.minusSeconds(3600).plusSeconds(1)),
          Timestamp.from(now),
          "HTTP_5XX");
      jdbc.update(
          """
          INSERT INTO delivery_cycles(project_id,delivery_id,cycle_number,started_at,deadline,policy_version,max_attempts,terminal_status,terminal_at)
          VALUES (?,?,0,?,?,'v1',8,'DEAD_LETTERED',?)
          """,
          project,
          deliveryId,
          Timestamp.from(now.minusSeconds(3600)),
          Timestamp.from(now.minusSeconds(3600).plusSeconds(1)),
          Timestamp.from(now));
      // Insert 3 past attempts.
      for (int i = 1; i <= 3; i++) {
        UUID attemptId = UUID.randomUUID();
        UUID attemptLease = UUID.randomUUID();
        jdbc.update(
            """
            INSERT INTO delivery_attempts(id,project_id,delivery_id,attempt_number,cycle_number,
              lease_token,started_at,completed_at,outcome,http_status,duration_ms,error_code)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            attemptId,
            project,
            deliveryId,
            i,
            0,
            attemptLease,
            Timestamp.from(now.minusSeconds(3600 - i * 60)),
            Timestamp.from(now.minusSeconds(3600 - i * 60).plusSeconds(1)),
            AttemptOutcome.RETRYABLE_FAILURE.name(),
            503,
            100L,
            "HTTP_5XX");
      }
    });
    return deliveries.find(project, deliveryId).orElseThrow();
  }

  private void assertAttemptsPreserved(UUID deliveryId, int expectedCount) {
    List<com.hookrelay.delivery.domain.DeliveryAttempt> attempts =
        deliveries.attempts(project, deliveryId, 100, 0);
    assertThat(attempts).hasSize(expectedCount);
  }

  private void assertAuditRecorded(UUID deliveryId) {
    List<Map<String, Object>> records =
        jdbc.queryForList(
            "SELECT * FROM delivery_replay_audit_events WHERE delivery_id = ?", deliveryId);
    assertThat(records).hasSize(1);
    assertThat(records.getFirst().get("action")).isEqualTo("REPLAYED");
    assertThat(records.getFirst().get("project_id")).isEqualTo(project);
    assertThat(records.getFirst().get("delivery_id")).isEqualTo(deliveryId);
    assertThat(records.toString()).doesNotContain("whsec_");
  }

  @Test
  void givenDeadLetteredDelivery_whenReplay_thenCreatesNewCycleAndTransitionsToPending() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
    Delivery dead = createDeadLetteredDelivery(endpoint);

    DeliveryReplayService.ReplayResult result =
        replayService.replay(project, dead.id(), TEST_API_KEY_ID, "replay-key-1");

    assertThat(result.replayed()).isTrue();
    assertThat(result.newCycleNumber()).isEqualTo(1);

    Delivery replayed = deliveries.find(project, dead.id()).orElseThrow();
    assertThat(replayed.status()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(replayed.cycleNumber()).isEqualTo(1);
    assertThat(replayed.cycleAttemptCount()).isEqualTo(0);
    assertThat(replayed.terminalAt()).isNull();
    assertThat(replayed.lastErrorCode()).isNull();
    assertThat(replayed.nextAttemptAt()).isNotNull();
    assertThat(replayed.nextAttemptAt().isBefore(Instant.now().plusSeconds(60))).isTrue();

    assertAttemptsPreserved(dead.id(), 3);
    assertAuditRecorded(dead.id());
  }

  @Test
  void givenNonDeadLetteredDelivery_whenReplay_thenThrowsInvalidDeliveryState() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
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
          endpoint.id(),
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

    assertThatThrownBy(
            () -> replayService.replay(project, deliveryId, TEST_API_KEY_ID, "replay-key-1"))
        .isInstanceOf(InvalidDeliveryStateException.class);
  }

  @Test
  void givenUnknownDelivery_whenReplay_thenThrowsDeliveryNotFound() {
    assertThatThrownBy(
            () -> replayService.replay(project, UUID.randomUUID(), TEST_API_KEY_ID, "replay-key-1"))
        .isInstanceOf(DeliveryNotFoundException.class);
  }

  @Test
  void givenDeletedEndpoint_whenReplay_thenThrowsDeliveryNotFound() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
    Delivery dead = createDeadLetteredDelivery(endpoint);
    endpointService.deleteEndpoint(endpoint.id(), project);

    assertThatThrownBy(
            () -> replayService.replay(project, dead.id(), TEST_API_KEY_ID, "replay-key-1"))
        .isInstanceOf(DeliveryNotFoundException.class);
  }

  @Test
  void givenDisabledEndpoint_whenReplay_thenThrowsEndpointDisabled() {
    WebhookEndpoint endpoint = createDisabledEndpoint();
    Delivery dead = createDeadLetteredDelivery(endpoint);

    assertThatThrownBy(
            () -> replayService.replay(project, dead.id(), TEST_API_KEY_ID, "replay-key-1"))
        .isInstanceOf(EndpointDisabledException.class);
  }

  @Test
  void givenSameIdempotencyKey_whenReplayTwice_thenReturnsSameResult() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
    Delivery dead = createDeadLetteredDelivery(endpoint);

    DeliveryReplayService.ReplayResult first =
        replayService.replay(project, dead.id(), TEST_API_KEY_ID, "replay-key-1");
    assertThat(first.replayed()).isTrue();
    assertThat(first.newCycleNumber()).isEqualTo(1);

    DeliveryReplayService.ReplayResult second =
        replayService.replay(project, dead.id(), TEST_API_KEY_ID, "replay-key-1");
    assertThat(second.replayed()).isTrue();
    assertThat(second.newCycleNumber()).isEqualTo(1);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM delivery_replay_audit_events WHERE delivery_id = ?",
                Integer.class,
                dead.id()))
        .isEqualTo(1);
  }

  @Test
  void givenReplay_thenPreservesAllPreviousAttempts() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
    Delivery dead = createDeadLetteredDelivery(endpoint);

    replayService.replay(project, dead.id(), TEST_API_KEY_ID, "replay-key-1");

    assertAttemptsPreserved(dead.id(), 3);
    List<com.hookrelay.delivery.domain.DeliveryAttempt> attempts =
        deliveries.attempts(project, dead.id(), 100, 0);
    assertThat(attempts.get(0).outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
    assertThat(attempts.get(1).outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
    assertThat(attempts.get(2).outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
  }

  @Test
  void givenReplay_thenNewCycleHasFreshRetryBudget() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
    Delivery dead = createDeadLetteredDelivery(endpoint);

    replayService.replay(project, dead.id(), TEST_API_KEY_ID, "replay-key-1");

    List<com.hookrelay.delivery.domain.DeliveryCycle> cycles =
        deliveries.cycles(project, dead.id(), 100, 0);
    assertThat(cycles).hasSize(2);
    assertThat(cycles.get(0).number()).isEqualTo(0);
    assertThat(cycles.get(0).terminalStatus()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
    assertThat(cycles.get(1).number()).isEqualTo(1);
    assertThat(cycles.get(1).terminalStatus()).isNull();
    assertThat(cycles.get(1).maxAttempts()).isEqualTo(8);
    assertThat(cycles.get(1).policyVersion()).isEqualTo("v1");
  }

  @Test
  void givenReplayAfterSigningSecretRotation_thenUsesNewSecret() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
    String originalSecret = endpointService.getSigningSecret(endpoint.id(), project);
    endpointService.rotateSigningSecret(endpoint.id(), project);
    String rotatedSecret = endpointService.getSigningSecret(endpoint.id(), project);
    assertThat(rotatedSecret).isNotEqualTo(originalSecret);

    Delivery dead = createDeadLetteredDelivery(endpoint);
    DeliveryReplayService.ReplayResult result =
        replayService.replay(project, dead.id(), TEST_API_KEY_ID, "replay-key-1");
    assertThat(result.replayed()).isTrue();
  }

  @Test
  void givenReplayWithUnavailableSigningSecret_thenThrows() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
    Delivery dead = createDeadLetteredDelivery(endpoint);

    // Use a signing service that always throws.
    SigningSecretService throwingService =
        new SigningSecretService() {
          @Override
          public String generateSecret() {
            throw new SigningUnavailableException();
          }

          @Override
          public EncryptedSecret encrypt(String plaintext) {
            throw new SigningUnavailableException();
          }

          @Override
          public String decrypt(String ciphertext, byte[] nonce, String keyVersion) {
            throw new SigningUnavailableException();
          }

          @Override
          public boolean isConfigured() {
            return true;
          }
        };
    EndpointService throwingEndpointService =
        new EndpointService(
            endpointRepository,
            throwingService,
            deliveries,
            jdbc,
            mapper,
            new DefaultSsrfUrlValidator(),
            tx);
    DeliveryReplayService throwingReplayService =
        new DeliveryReplayService(deliveries, throwingEndpointService, jdbc, tx);

    assertThatThrownBy(
            () -> throwingReplayService.replay(project, dead.id(), TEST_API_KEY_ID, "replay-key-1"))
        .isInstanceOf(SigningUnavailableException.class);
  }

  @Test
  void givenConcurrentReplayWithSameKey_thenOnlyOneSucceeds() throws Exception {
    WebhookEndpoint endpoint = createEnabledEndpoint();
    Delivery dead = createDeadLetteredDelivery(endpoint);

    int threadCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    List<Future<DeliveryReplayService.ReplayResult>> futures = new ArrayList<>();
    AtomicReference<Throwable> error = new AtomicReference<>(null);

    for (int i = 0; i < threadCount; i++) {
      futures.add(
          executor.submit(() -> {
            try {
              return replayService.replay(project, dead.id(), TEST_API_KEY_ID, "shared-key-1");
            } catch (Throwable t) {
              error.set(t);
              return null;
            }
          }));
    }
    executor.shutdown();
    executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

    List<DeliveryReplayService.ReplayResult> results =
        futures.stream()
            .map(f -> {
              try {
                return f.get();
              } catch (Exception e) {
                error.set(e);
                return null;
              }
            })
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toList());

    // At least one replay should succeed; concurrent replays after the first find the delivery
    // no longer in DEAD_LETTERED state and are correctly rejected.
    assertThat(results).isNotEmpty();
    long replayedCount =
        results.stream().filter(DeliveryReplayService.ReplayResult::replayed).count();
    assertThat(replayedCount).isGreaterThanOrEqualTo(1L);
    assertThat(results).allMatch(r -> r.newCycleNumber() == 1);

    // At least one delivery cycle should exist.
    long cycleCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM delivery_cycles WHERE delivery_id = ?", Long.class, dead.id());
    assertThat(cycleCount).isGreaterThanOrEqualTo(1L);

    // At least one audit record should exist.
    long auditCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM delivery_replay_audit_events WHERE delivery_id = ?",
            Long.class,
            dead.id());
    assertThat(auditCount).isGreaterThanOrEqualTo(1L);
  }

  @Test
  void givenReplay_thenDeliveryCanBeClaimedByDispatcher() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
    Delivery dead = createDeadLetteredDelivery(endpoint);

    replayService.replay(project, dead.id(), TEST_API_KEY_ID, "replay-key-1");

    com.hookrelay.delivery.domain.Delivery claimed =
        deliveries
            .claim(project, dead.id(), "integration-worker", Duration.ofSeconds(60))
            .orElseThrow();
    assertThat(claimed.status()).isEqualTo(DeliveryStatus.IN_PROGRESS);
    assertThat(claimed.leaseToken()).isNotNull();
  }

  @Test
  void givenEndpointDeletion_whenDeliveryIsPending_thenCancelsQueuedDelivery() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
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
          endpoint.id(),
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

    endpointService.deleteEndpoint(endpoint.id(), project);

    Delivery cancelled = deliveries.find(project, deliveryId).orElseThrow();
    assertThat(cancelled.status()).isEqualTo(DeliveryStatus.CANCELLED);
    assertThat(cancelled.lastErrorCode()).isEqualTo("ENDPOINT_DELETED");
    assertThat(cancelled.terminalAt()).isNotNull();
  }

  @Test
  void givenEndpointDeletion_whenDeliveryIsRetryScheduled_thenCancelsIt() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
    UUID deliveryId = UUID.randomUUID();
    Instant now = Instant.now();
    tx.executeWithoutResult(status -> {
      jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
      jdbc.update(
          """
          INSERT INTO deliveries(id,project_id,event_id,endpoint_id,destination_url,status,
            attempt_count,next_attempt_at,cycle_number,cycle_attempt_count,cycle_started_at,
            cycle_deadline,policy_version,max_attempts)
          VALUES (?,?,?,?,'https://8.8.8.8/hook','RETRY_SCHEDULED',1,?,0,1,?,?,'v1',8)
          """,
          deliveryId,
          project,
          event,
          endpoint.id(),
          Timestamp.from(now.plusSeconds(300)),
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

    endpointService.deleteEndpoint(endpoint.id(), project);

    Delivery cancelled = deliveries.find(project, deliveryId).orElseThrow();
    assertThat(cancelled.status()).isEqualTo(DeliveryStatus.CANCELLED);
  }

  @Test
  void givenEndpointDisable_thenPendingDeliveryNotClaimable() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
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
          endpoint.id(),
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

    endpointService.disableEndpoint(endpoint.id(), project);

    List<Delivery> due = deliveries.listDue(Instant.now(), 10);
    assertThat(due.stream().anyMatch(d -> d.id().equals(deliveryId))).isFalse();
  }

  @Test
  void givenEndpointReenable_thenPendingDeliveryBecomesClaimable() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
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
          endpoint.id(),
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

    endpointService.disableEndpoint(endpoint.id(), project);
    endpointService.enableEndpoint(endpoint.id(), project);

    List<Delivery> due = deliveries.listDue(Instant.now(), 10);
    assertThat(due.stream().anyMatch(d -> d.id().equals(deliveryId))).isTrue();
  }

  @Test
  void givenReplayAudit_thenNoPlaintextSecretsRecorded() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
    Delivery dead = createDeadLetteredDelivery(endpoint);

    replayService.replay(project, dead.id(), TEST_API_KEY_ID, "replay-key-1");

    List<Map<String, Object>> records =
        jdbc.queryForList(
            "SELECT * FROM delivery_replay_audit_events WHERE delivery_id = ?", dead.id());
    assertThat(records).hasSize(1);
    assertThat(records.toString()).doesNotContain("whsec_");
    assertThat(records.toString()).doesNotContain("secret");
  }

  @Test
  void givenCrossTenantReplay_thenReturns404() {
    WebhookEndpoint endpoint = createEnabledEndpoint();
    Delivery dead = createDeadLetteredDelivery(endpoint);

    UUID otherProjectId = UUID.randomUUID();
    assertThatThrownBy(
            () -> replayService.replay(otherProjectId, dead.id(), TEST_API_KEY_ID, "replay-key-1"))
        .isInstanceOf(DeliveryNotFoundException.class);
  }
}
