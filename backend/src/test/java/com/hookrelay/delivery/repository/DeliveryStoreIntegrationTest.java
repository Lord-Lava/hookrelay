package com.hookrelay.delivery.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hookrelay.delivery.domain.AttemptOutcome;
import com.hookrelay.delivery.domain.Delivery;
import com.hookrelay.delivery.domain.DeliveryPolicy;
import com.hookrelay.delivery.domain.DeliveryStatus;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DeliveryStoreIntegrationTest extends AbstractDeliveryIntegrationTest {
  private FanoutStore fanouts;
  private UUID foreignProject;
  private final DeliveryPolicy policy = new DeliveryPolicy("v1", 8, Duration.ofHours(24));
  private final byte[] body = "{\"data\":{}}".getBytes(StandardCharsets.UTF_8);

  @BeforeEach
  protected void setUp() {
    super.setUp();
    foreignProject = UUID.randomUUID();
    seedProject(foreignProject, organizationId, foreignProject.toString());
    fanouts = new FanoutStore(jdbc, tx);
    jdbc.update(
        """
        INSERT INTO endpoints(id,project_id,url,signing_secret_ciphertext,signing_secret_nonce,signing_secret_key_version)
        VALUES (?,?,'https://8.8.8.8/test','synthetic-ciphertext',?,'v1')
        """,
        endpoint,
        project,
        new byte[12]);
  }

  private Delivery create() {
    fanouts.create(project, event, List.of(endpoint), body, policy);
    return deliveries.list(project, 100, 0).getFirst();
  }

  private Delivery claim(UUID id) {
    return deliveries.claim(project, id, "test-worker", Duration.ofSeconds(60)).orElseThrow();
  }

  @Test
  void createsSnapshotDeliveryAndInitialCycleAtomically() {
    var d = create();
    assertThat(d.status()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(d.attemptCount()).isZero();
    assertThat(d.destinationUrl()).isEqualTo("https://8.8.8.8/test");
    assertThat(deliveries.cycles(project, d.id(), 100, 0)).hasSize(1);
    assertThat(fanouts.find(project, event).orElseThrow().body()).isEqualTo(body);
    jdbc.update(
        "UPDATE endpoints SET url='https://8.8.8.8/new' WHERE project_id=? AND id=?",
        project,
        endpoint);
    assertThat(deliveries.find(project, d.id()).orElseThrow().destinationUrl()).endsWith("/test");
  }

  @Test
  void zeroRecipientsRemainFinalWhenReplayedWithNewRecipients() {
    fanouts.create(project, event, List.of(), body, policy);
    var replay =
        fanouts.create(
            project, event, List.of(endpoint), "{}".getBytes(StandardCharsets.UTF_8), policy);
    assertThat(replay.recipientCount()).isZero();
    assertThat(replay.body()).isEqualTo(body);
    assertThat(deliveries.list(project, 100, 0)).isEmpty();
  }

  @Test
  void concurrentFanoutsCreateOnePairAndOneCycle() throws Exception {
    race(() -> fanouts.create(project, event, List.of(endpoint), body, policy));
    assertThat(deliveries.list(project, 100, 0)).hasSize(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_cycles", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void rollbackIncludesTheMarkerAndAllChildren() {
    tx.executeWithoutResult(status -> {
      create();
      status.setRollbackOnly();
    });
    assertThat(fanouts.find(project, event)).isEmpty();
    assertThat(deliveries.list(project, 100, 0)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_cycles", Integer.class)).isZero();
  }

  @Test
  void rejectsForeignOrInactiveRecipientsWithoutPartialWrites() {
    UUID foreignEndpoint = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO endpoints(id,project_id,url,signing_secret_ciphertext,enabled) VALUES (?,?,'https://8.8.4.4/','',false)
        """,
        foreignEndpoint,
        foreignProject);
    assertThatThrownBy(
            () -> fanouts.create(project, event, List.of(endpoint, foreignEndpoint), body, policy))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(fanouts.find(project, event)).isEmpty();
    assertThat(deliveries.list(project, 100, 0)).isEmpty();
    assertThatThrownBy(() -> fanouts.create(foreignProject, event, List.of(), body, policy))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void crossTenantReadsAndMutationsAreDenied() {
    Delivery d = claim(create().id());
    var a = deliveries.startAttempt(project, d.id(), d.leaseToken()).orElseThrow();
    assertThat(deliveries.find(foreignProject, d.id())).isEmpty();
    assertThat(deliveries.list(foreignProject, 100, 0)).isEmpty();
    assertThat(deliveries.attempts(foreignProject, d.id(), 100, 0)).isEmpty();
    assertThat(deliveries.cycles(foreignProject, d.id(), 100, 0)).isEmpty();
    assertThat(fanouts.find(foreignProject, event)).isEmpty();
    assertThat(deliveries.claim(foreignProject, d.id(), "other", Duration.ofSeconds(60))).isEmpty();
    assertThat(deliveries.startAttempt(foreignProject, d.id(), d.leaseToken())).isEmpty();
    assertThat(
            deliveries.finishAttempt(
                foreignProject,
                d.id(),
                d.leaseToken(),
                a.id(),
                AttemptOutcome.SUCCEEDED,
                200,
                1,
                null,
                null))
        .isFalse();
    assertThat(deliveries.recoverExpired(foreignProject, d.id(), d.leaseToken())).isFalse();
    assertThat(deliveries.cancel(foreignProject, d.id(), "CANCELLED")).isFalse();
    assertThat(deliveries.expireHorizon(foreignProject, d.id())).isFalse();
    assertThat(deliveries.find(project, d.id()).orElseThrow().status())
        .isEqualTo(DeliveryStatus.IN_PROGRESS);
  }

  @Test
  void onlyOneConcurrentClaimAndStartSucceeds() throws Exception {
    UUID id = create().id();
    var claims = race(() -> deliveries.claim(project, id, "test", Duration.ofSeconds(60)));
    assertThat(claims.stream().filter(Optional::isPresent).count()).isEqualTo(1);
    var d = deliveries.find(project, id).orElseThrow();
    var starts = race(() -> deliveries.startAttempt(project, id, d.leaseToken()));
    assertThat(starts.stream().filter(Optional::isPresent).count()).isEqualTo(1);
    assertThat(deliveries.find(project, id).orElseThrow().attemptCount()).isEqualTo(1);
  }

  @Test
  void concurrentCompletionsFinalizeExactlyOneStoredResult() throws Exception {
    Delivery d = claim(create().id());
    var a = deliveries.startAttempt(project, d.id(), d.leaseToken()).orElseThrow();
    var results =
        race(() ->
            deliveries.finishAttempt(
                project,
                d.id(),
                d.leaseToken(),
                a.id(),
                AttemptOutcome.SUCCEEDED,
                204,
                12,
                null,
                null));
    assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    assertThat(deliveries.attempts(project, d.id(), 100, 0)).hasSize(1);
    assertThat(deliveries.cycles(project, d.id(), 100, 0).getFirst().terminalStatus())
        .isEqualTo(DeliveryStatus.DELIVERED);
  }

  @Test
  void successFinalizesAttemptDeliveryAndCycleWithoutAllowingAnotherClaim() {
    Delivery d = claim(create().id());
    assertThat(d.attemptCount()).isZero();
    var a = deliveries.startAttempt(project, d.id(), d.leaseToken()).orElseThrow();
    assertThat(
            deliveries.finishAttempt(
                project,
                d.id(),
                d.leaseToken(),
                a.id(),
                AttemptOutcome.SUCCEEDED,
                204,
                12,
                null,
                null))
        .isTrue();
    assertThat(
            deliveries.finishAttempt(
                project,
                d.id(),
                d.leaseToken(),
                a.id(),
                AttemptOutcome.SUCCEEDED,
                204,
                12,
                null,
                null))
        .isFalse();
    var completed = deliveries.find(project, d.id()).orElseThrow();
    assertThat(completed.status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(completed.leaseToken()).isNull();
    assertThat(completed.nextAttemptAt()).isNull();
    assertThat(deliveries.attempts(project, d.id(), 100, 0).getFirst().outcome())
        .isEqualTo(AttemptOutcome.SUCCEEDED);
    assertThat(deliveries.cycles(project, d.id(), 100, 0).getFirst().terminalStatus())
        .isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(deliveries.claim(project, d.id(), "test", Duration.ofSeconds(60))).isEmpty();
  }

  @Test
  void expiredWorkerCannotStartOrCompleteAndRecoveryFencesItsToken() {
    Delivery d = claim(create().id());
    var a = deliveries.startAttempt(project, d.id(), d.leaseToken()).orElseThrow();
    jdbc.update(
        "UPDATE deliveries SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE project_id=? AND id=?",
        project,
        d.id());
    assertThat(deliveries.startAttempt(project, d.id(), d.leaseToken())).isEmpty();
    assertThat(
            deliveries.finishAttempt(
                project,
                d.id(),
                d.leaseToken(),
                a.id(),
                AttemptOutcome.SUCCEEDED,
                200,
                1,
                null,
                null))
        .isFalse();
    assertThat(deliveries.recoverExpired(project, d.id(), d.leaseToken())).isTrue();
    assertThat(deliveries.attempts(project, d.id(), 100, 0).getFirst().outcome())
        .isEqualTo(AttemptOutcome.UNKNOWN);
    jdbc.update(
        "UPDATE deliveries SET next_attempt_at=clock_timestamp() WHERE project_id=? AND id=?",
        project,
        d.id());
    Delivery next = claim(d.id());
    assertThat(next.leaseToken()).isNotEqualTo(d.leaseToken());
    assertThat(deliveries.startAttempt(project, d.id(), d.leaseToken())).isEmpty();
    assertThat(
            deliveries
                .startAttempt(project, d.id(), next.leaseToken())
                .orElseThrow()
                .attemptNumber())
        .isEqualTo(2);
  }

  @Test
  void cancellationWinsOverLateCompletionAndRetainsDeletedEndpointHistory() {
    Delivery d = claim(create().id());
    var a = deliveries.startAttempt(project, d.id(), d.leaseToken()).orElseThrow();
    jdbc.update(
        "UPDATE endpoints SET deleted_at=clock_timestamp() WHERE project_id=? AND id=?",
        project,
        endpoint);
    assertThat(deliveries.cancel(project, d.id(), "ENDPOINT_DELETED")).isTrue();
    assertThat(
            deliveries.finishAttempt(
                project,
                d.id(),
                d.leaseToken(),
                a.id(),
                AttemptOutcome.SUCCEEDED,
                200,
                1,
                null,
                null))
        .isFalse();
    assertThat(deliveries.find(project, d.id()).orElseThrow().status())
        .isEqualTo(DeliveryStatus.CANCELLED);
    assertThat(deliveries.attempts(project, d.id(), 100, 0)).hasSize(1);
  }

  @Test
  void disabledEndpointCannotBeClaimedOrStarted() {
    Delivery d = create();
    jdbc.update(
        "UPDATE endpoints SET enabled=false WHERE project_id=? AND id=?", project, endpoint);
    assertThat(deliveries.claim(project, d.id(), "test", Duration.ofSeconds(60))).isEmpty();
    jdbc.update("UPDATE endpoints SET enabled=true WHERE project_id=? AND id=?", project, endpoint);
    Delivery claimed = claim(d.id());
    jdbc.update(
        "UPDATE endpoints SET enabled=false WHERE project_id=? AND id=?", project, endpoint);
    assertThat(deliveries.startAttempt(project, d.id(), claimed.leaseToken())).isEmpty();
  }

  @Test
  void databaseRejectsCrossProjectReferencesInvalidStateAndRawResponseStorage() {
    Delivery d = create();
    assertThatThrownBy(() ->
            jdbc.update("UPDATE deliveries SET project_id=? WHERE id=?", foreignProject, d.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("UPDATE deliveries SET status='OTHER' WHERE id=?", d.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE deliveries SET status='IN_PROGRESS' WHERE id=?", d.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    Delivery claimed = claim(d.id());
    var a = deliveries.startAttempt(project, d.id(), claimed.leaseToken()).orElseThrow();
    assertThatThrownBy(() ->
            jdbc.update(
                "UPDATE delivery_attempts SET response_body='private data' WHERE id=?", a.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() ->
            jdbc.update(
                "UPDATE delivery_attempts SET project_id=? WHERE id=?", foreignProject, a.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void failedResultTransactionRollsBackAttemptAndStatusTogether() {
    Delivery d = claim(create().id());
    var a = deliveries.startAttempt(project, d.id(), d.leaseToken()).orElseThrow();
    tx.executeWithoutResult(status -> {
      assertThat(
              deliveries.finishAttempt(
                  project,
                  d.id(),
                  d.leaseToken(),
                  a.id(),
                  AttemptOutcome.PERMANENT_FAILURE,
                  400,
                  1,
                  "HTTP_4XX",
                  null))
          .isTrue();
      status.setRollbackOnly();
    });
    assertThat(deliveries.find(project, d.id()).orElseThrow().status())
        .isEqualTo(DeliveryStatus.IN_PROGRESS);
    assertThat(deliveries.attempts(project, d.id(), 100, 0).getFirst().outcome())
        .isEqualTo(AttemptOutcome.STARTED);
    assertThat(deliveries.cycles(project, d.id(), 100, 0).getFirst().terminalStatus()).isNull();
  }

  @Test
  void retryResultPersistsDueTimeAndFinalAttemptExhaustsBudget() {
    fanouts.create(
        project, event, List.of(endpoint), body, new DeliveryPolicy("v1", 2, Duration.ofHours(1)));
    Delivery d = claim(deliveries.list(project, 100, 0).getFirst().id());
    var first = deliveries.startAttempt(project, d.id(), d.leaseToken()).orElseThrow();
    Instant due = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
    assertThat(
            deliveries.finishAttempt(
                project,
                d.id(),
                d.leaseToken(),
                first.id(),
                AttemptOutcome.RETRYABLE_FAILURE,
                503,
                10,
                "HTTP_5XX",
                due))
        .isTrue();
    var scheduled = deliveries.find(project, d.id()).orElseThrow();
    assertThat(scheduled.status()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    assertThat(scheduled.nextAttemptAt()).isEqualTo(due.truncatedTo(ChronoUnit.MICROS));
    assertThat(deliveries.claim(project, d.id(), "too-early", Duration.ofSeconds(60))).isEmpty();
    jdbc.update(
        "UPDATE deliveries SET next_attempt_at=clock_timestamp() WHERE project_id=? AND id=?",
        project,
        d.id());
    Delivery next = claim(d.id());
    var second = deliveries.startAttempt(project, d.id(), next.leaseToken()).orElseThrow();
    assertThat(
            deliveries.finishAttempt(
                project,
                d.id(),
                next.leaseToken(),
                second.id(),
                AttemptOutcome.RETRYABLE_FAILURE,
                503,
                10,
                "HTTP_5XX",
                due))
        .isTrue();
    assertThat(deliveries.find(project, d.id()).orElseThrow().status())
        .isEqualTo(DeliveryStatus.DEAD_LETTERED);
    assertThat(deliveries.attempts(project, d.id(), 100, 0)).hasSize(2);
    assertThat(deliveries.cycles(project, d.id(), 100, 0).getFirst().terminalStatus())
        .isEqualTo(DeliveryStatus.DEAD_LETTERED);
    assertThat(deliveries.claim(project, d.id(), "exhausted", Duration.ofSeconds(60))).isEmpty();
  }

  @Test
  void abandonedClaimBeforeStartConsumesNoAttempt() {
    Delivery d = claim(create().id());
    jdbc.update(
        "UPDATE deliveries SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE project_id=? AND id=?",
        project,
        d.id());
    assertThat(deliveries.recoverExpired(project, d.id(), d.leaseToken())).isTrue();
    assertThat(deliveries.recoverExpired(project, d.id(), d.leaseToken())).isFalse();
    assertThat(deliveries.find(project, d.id()).orElseThrow().attemptCount()).isZero();
    assertThat(deliveries.attempts(project, d.id(), 100, 0)).isEmpty();
  }

  @Test
  void expiresDisabledPendingWorkWithoutInventingAttempts() {
    Delivery d = create();
    assertThat(deliveries.expireHorizon(project, d.id())).isFalse();
    jdbc.update(
        "UPDATE endpoints SET enabled=false WHERE project_id=? AND id=?", project, endpoint);
    tx.executeWithoutResult(status -> {
      jdbc.update(
          """
          UPDATE deliveries SET cycle_started_at=clock_timestamp()-interval '2 hours',
          cycle_deadline=clock_timestamp()-interval '1 hour',next_attempt_at=clock_timestamp()-interval '90 minutes'
          WHERE project_id=? AND id=?
          """,
          project,
          d.id());
      jdbc.update(
          """
          UPDATE delivery_cycles SET started_at=clock_timestamp()-interval '2 hours',deadline=clock_timestamp()-interval '1 hour'
          WHERE project_id=? AND delivery_id=?
          """,
          project,
          d.id());
    });
    assertThat(deliveries.expireHorizon(project, d.id())).isTrue();
    assertThat(deliveries.expireHorizon(project, d.id())).isFalse();
    assertThat(deliveries.find(project, d.id()).orElseThrow().status())
        .isEqualTo(DeliveryStatus.DEAD_LETTERED);
    assertThat(deliveries.attempts(project, d.id(), 100, 0)).isEmpty();
  }

  @Test
  void databaseEnforcesPairUniquenessAndRequiresInitialCycleAtCommit() {
    Delivery d = create();
    assertThatThrownBy(() ->
            jdbc.update(
                """
                INSERT INTO deliveries(id,project_id,event_id,endpoint_id,destination_url,status,attempt_count,
                  next_attempt_at,cycle_number,cycle_attempt_count,cycle_started_at,cycle_deadline,policy_version,max_attempts)
                SELECT ?,project_id,event_id,endpoint_id,destination_url,status,attempt_count,next_attempt_at,
                  cycle_number,cycle_attempt_count,cycle_started_at,cycle_deadline,policy_version,max_attempts
                FROM deliveries WHERE project_id=? AND id=?
                """,
                UUID.randomUUID(),
                project,
                d.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() ->
            jdbc.update(
                "DELETE FROM delivery_cycles WHERE project_id=? AND delivery_id=?",
                project,
                d.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    // History deletion is explicit: deleting the owning delivery removes attempts/cycles together.
    assertThat(jdbc.update("DELETE FROM deliveries WHERE project_id=? AND id=?", project, d.id()))
        .isEqualTo(1);
    assertThat(deliveries.cycles(project, d.id(), 100, 0)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void upgradesV7ButRefusesUnexpectedLegacyDeliveryHistory(boolean existingHistory) {
    String legacy = "legacy_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE SCHEMA " + legacy);
    var legacySource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema="
                + legacy,
            postgres.getUsername(),
            postgres.getPassword());
    var old = new JdbcTemplate(legacySource);
    try {
      Flyway.configure()
          .dataSource(legacySource)
          .schemas(legacy)
          .defaultSchema(legacy)
          .target("7")
          .load()
          .migrate();
      UUID org = UUID.randomUUID();
      old.update("INSERT INTO organizations(id,name) VALUES (?,'legacy')", org);
      old.update(
          "INSERT INTO projects(id,organization_id,name,environment) VALUES (?,?,'legacy','development')",
          project,
          org);
      old.update(
          "INSERT INTO endpoints(id,project_id,url,signing_secret_ciphertext,enabled) VALUES (?,?,'https://8.8.8.8/','',false)",
          endpoint,
          project);
      old.update(
          "INSERT INTO events(id,project_id,event_type,payload,request_fingerprint) VALUES (?,?,'legacy','{}',?)",
          event,
          project,
          "a".repeat(64));
      var upgrade =
          Flyway.configure().dataSource(legacySource).schemas(legacy).defaultSchema(legacy).load();
      if (existingHistory) {
        old.update(
            "INSERT INTO deliveries(id,event_id,endpoint_id,status) VALUES (?,?,?,'PENDING')",
            UUID.randomUUID(),
            event,
            endpoint);
        assertThatThrownBy(upgrade::migrate).hasStackTraceContaining("operator-reviewed backfill");
        assertThat(old.queryForObject("SELECT count(*) FROM deliveries", Integer.class))
            .isEqualTo(1);
      } else {
        upgrade.migrate();
        assertThat(old.queryForObject("SELECT count(*) FROM deliveries", Integer.class)).isZero();
        assertThat(
                old.queryForObject(
                    "SELECT count(*) FROM events WHERE project_id=? AND id=?",
                    Integer.class,
                    project,
                    event))
            .isEqualTo(1);
        assertThat(
                old.queryForObject(
                    "SELECT count(*) FROM endpoints WHERE project_id=? AND id=?",
                    Integer.class,
                    project,
                    endpoint))
            .isEqualTo(1);
      }
      assertThat(
              old.queryForObject(
                  "SELECT max(version::int) FROM flyway_schema_history WHERE success",
                  Integer.class))
          .isEqualTo(existingHistory ? 7 : 11);
    } finally {
      jdbc.execute("DROP SCHEMA " + legacy + " CASCADE");
    }
  }

  @Test
  void retryHorizonExceededDeadLettersWithoutAttempt() {
    Delivery d = create();
    Instant now = Instant.now();
    tx.executeWithoutResult(status -> {
      jdbc.update(
          """
          UPDATE deliveries SET status='RETRY_SCHEDULED', next_attempt_at=?, cycle_deadline=?,
            cycle_started_at=?
          WHERE project_id=? AND id=?
          """,
          Timestamp.from(now.minusSeconds(120)),
          Timestamp.from(now.minusSeconds(60)),
          Timestamp.from(now.minusSeconds(300)),
          project,
          d.id());
      jdbc.update(
          """
          UPDATE delivery_cycles SET started_at=?, deadline=? WHERE project_id=? AND delivery_id=? AND cycle_number=0
          """,
          Timestamp.from(now.minusSeconds(300)),
          Timestamp.from(now.minusSeconds(60)),
          project,
          d.id());
    });
    assertThat(deliveries.expireHorizon(project, d.id())).isTrue();
    assertThat(deliveries.find(project, d.id()).orElseThrow().status())
        .isEqualTo(DeliveryStatus.DEAD_LETTERED);
    assertThat(deliveries.attempts(project, d.id(), 100, 0)).isEmpty();
    assertThat(deliveries.cycles(project, d.id(), 100, 0).getFirst().terminalStatus())
        .isEqualTo(DeliveryStatus.DEAD_LETTERED);
    assertThat(deliveries.expireHorizon(project, d.id())).isFalse();
  }

  @Test
  void deadLetteredStatePreservesDiagnosticInfoAndAttemptHistory() {
    Delivery d = create();
    Delivery claimed = claim(d.id());
    var first = deliveries.startAttempt(project, d.id(), claimed.leaseToken()).orElseThrow();
    Instant due = Instant.now().plusSeconds(60);
    assertThat(
            deliveries.finishAttempt(
                project,
                d.id(),
                claimed.leaseToken(),
                first.id(),
                AttemptOutcome.PERMANENT_FAILURE,
                400,
                10,
                "HTTP_4XX",
                null))
        .isTrue();
    var dead = deliveries.find(project, d.id()).orElseThrow();
    assertThat(dead.status()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
    assertThat(dead.lastErrorCode()).isEqualTo("HTTP_4XX");
    assertThat(dead.terminalAt()).isNotNull();
    assertThat(dead.nextAttemptAt()).isNull();
    assertThat(dead.leaseToken()).isNull();

    var attempts = deliveries.attempts(project, d.id(), 100, 0);
    assertThat(attempts).hasSize(1);
    assertThat(attempts.getFirst().outcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
    assertThat(attempts.getFirst().httpStatus()).isEqualTo(400);
    assertThat(attempts.getFirst().errorCode()).isEqualTo("HTTP_4XX");
    assertThat(attempts.getFirst().durationMs()).isEqualTo(10L);

    assertThat(
            deliveries.finishAttempt(
                project,
                d.id(),
                claimed.leaseToken(),
                first.id(),
                AttemptOutcome.PERMANENT_FAILURE,
                400,
                10,
                "HTTP_4XX",
                null))
        .isFalse();
    assertThat(deliveries.find(project, d.id()).orElseThrow().status())
        .isEqualTo(DeliveryStatus.DEAD_LETTERED);
    assertThat(deliveries.attempts(project, d.id(), 100, 0)).hasSize(1);
  }

  @Test
  void retryableFailureReschedulesAndRestartRecoveryResumes() {
    Delivery d = create();
    Delivery claimed = claim(d.id());
    var first = deliveries.startAttempt(project, d.id(), claimed.leaseToken()).orElseThrow();
    Instant due = Instant.now().plusSeconds(60);
    assertThat(
            deliveries.finishAttempt(
                project,
                d.id(),
                claimed.leaseToken(),
                first.id(),
                AttemptOutcome.RETRYABLE_FAILURE,
                503,
                10,
                "HTTP_5XX",
                due))
        .isTrue();
    var scheduled = deliveries.find(project, d.id()).orElseThrow();
    assertThat(scheduled.status()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    assertThat(scheduled.nextAttemptAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
        .isEqualTo(due.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    assertThat(deliveries.claim(project, d.id(), "too-early", Duration.ofSeconds(60))).isEmpty();

    jdbc.update(
        "UPDATE deliveries SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE project_id=? AND id=?",
        project,
        d.id());
    DeliveryStore restarted = new DeliveryStore(jdbc, tx, retryScheduler);
    Delivery reclaimed =
        restarted.claim(project, d.id(), "restarted-worker", Duration.ofSeconds(60)).orElseThrow();
    assertThat(reclaimed.leaseToken()).isNotEqualTo(claimed.leaseToken());
    assertThat(reclaimed.status()).isEqualTo(DeliveryStatus.IN_PROGRESS);
    assertThat(reclaimed.nextAttemptAt()).isNull();
  }

  @Test
  void multipleAttemptsPreserveFullHistory() {
    Delivery d = create();
    jdbc.update(
        "UPDATE deliveries SET max_attempts=3 WHERE project_id=? AND id=?", project, d.id());
    jdbc.update(
        "UPDATE delivery_cycles SET max_attempts=3 WHERE project_id=? AND delivery_id=? AND cycle_number=0",
        project,
        d.id());

    Optional<Delivery> claimed =
        deliveries.claim(project, d.id(), "test-worker", Duration.ofSeconds(60));
    assertThat(claimed).isPresent();
    Delivery claim1 = claimed.orElseThrow();
    var a1 = deliveries.startAttempt(project, d.id(), claim1.leaseToken()).orElseThrow();
    Instant t1 = Instant.now();
    deliveries.finishAttempt(
        project,
        d.id(),
        claim1.leaseToken(),
        a1.id(),
        AttemptOutcome.RETRYABLE_FAILURE,
        503,
        10,
        "HTTP_5XX",
        t1.plusSeconds(30));

    jdbc.update(
        "UPDATE deliveries SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE project_id=? AND id=?",
        project,
        d.id());
    Optional<Delivery> claimed2 =
        deliveries.claim(project, d.id(), "test-worker", Duration.ofSeconds(60));
    assertThat(claimed2).isPresent();
    Delivery claim2 = claimed2.orElseThrow();
    var a2 = deliveries.startAttempt(project, d.id(), claim2.leaseToken()).orElseThrow();
    deliveries.finishAttempt(
        project,
        d.id(),
        claim2.leaseToken(),
        a2.id(),
        AttemptOutcome.RETRYABLE_FAILURE,
        503,
        12,
        "HTTP_5XX",
        Instant.now().plusSeconds(60));

    jdbc.update(
        "UPDATE deliveries SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE project_id=? AND id=?",
        project,
        d.id());
    Optional<Delivery> claimed3 =
        deliveries.claim(project, d.id(), "test-worker", Duration.ofSeconds(60));
    assertThat(claimed3).isPresent();
    Delivery claim3 = claimed3.orElseThrow();
    var a3 = deliveries.startAttempt(project, d.id(), claim3.leaseToken()).orElseThrow();
    deliveries.finishAttempt(
        project,
        d.id(),
        claim3.leaseToken(),
        a3.id(),
        AttemptOutcome.PERMANENT_FAILURE,
        503,
        8,
        "HTTP_5XX",
        null);

    var attempts = deliveries.attempts(project, d.id(), 100, 0);
    assertThat(attempts).hasSize(3);
    assertThat(attempts.get(0).attemptNumber()).isEqualTo(1);
    assertThat(attempts.get(0).outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
    assertThat(attempts.get(1).attemptNumber()).isEqualTo(2);
    assertThat(attempts.get(2).attemptNumber()).isEqualTo(3);
    assertThat(attempts.get(2).outcome()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
    assertThat(deliveries.find(project, d.id()).orElseThrow().status())
        .isEqualTo(DeliveryStatus.DEAD_LETTERED);
    assertThat(deliveries.cycles(project, d.id(), 100, 0).getFirst().terminalStatus())
        .isEqualTo(DeliveryStatus.DEAD_LETTERED);
  }

  private static <T> List<T> race(Callable<T> operation) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<T>>();
      for (int i = 0; i < 4; i++)
        futures.add(
            executor.submit(() -> {
              start.await();
              return operation.call();
            }));
      start.countDown();
      var result = new ArrayList<T>();
      for (var future : futures) result.add(future.get(20, TimeUnit.SECONDS));
      return result;
    }
  }
}
