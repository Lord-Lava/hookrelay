package com.hookrelay.delivery.repository;

import com.hookrelay.delivery.domain.AttemptOutcome;
import com.hookrelay.delivery.domain.Delivery;
import com.hookrelay.delivery.domain.DeliveryAttempt;
import com.hookrelay.delivery.domain.DeliveryCycle;
import com.hookrelay.delivery.domain.DeliveryStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scoped persistence primitives, not a dispatcher or retry policy. All mutations use the shared
 * project-control, endpoint-control, endpoint, delivery lock order. No method performs network I/O.
 */
@Repository
public class DeliveryStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public DeliveryStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  public Optional<Delivery> find(UUID projectId, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM deliveries WHERE project_id = ? AND id = ?",
            (rs, row) -> delivery(rs),
            projectId,
            id)
        .stream()
        .findFirst();
  }

  public List<Delivery> list(UUID projectId, int limit, long offset) {
    checkPage(limit, offset);
    return jdbc.query(
        """
        SELECT * FROM deliveries WHERE project_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
        """,
        (rs, row) -> delivery(rs),
        projectId,
        limit,
        offset);
  }

  /**
   * Lists due deliveries ready for claiming: PENDING or RETRY_SCHEDULED with next_attempt_at at or
   * before now, within cycle deadline, and below max attempts. Ordered by next_attempt_at then
   * created_at for fairness. Callers should claim each with {@link #claim(UUID, UUID, String,
   * java.time.Duration)} which performs row-level locking.
   */
  public List<Delivery> listDue(Instant now, int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid limit");
    return jdbc.query(
        """
        SELECT * FROM deliveries
        WHERE status IN ('PENDING', 'RETRY_SCHEDULED')
          AND next_attempt_at IS NOT NULL
          AND next_attempt_at <= ?
          AND cycle_deadline > ?
          AND cycle_attempt_count < max_attempts
          AND lease_owner IS NULL
        ORDER BY next_attempt_at ASC, created_at ASC, id ASC
        LIMIT ?
        """,
        (rs, row) -> delivery(rs),
        Timestamp.from(now),
        Timestamp.from(now),
        limit);
  }

  /**
   * Lists in-progress deliveries with expired leases for recovery. Callers should reclaim each with
   * {@link #recoverExpired(UUID, UUID, UUID, java.time.Instant)}.
   */
  public List<Delivery> listExpiredLeases(Instant now, int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid limit");
    return jdbc.query(
        """
        SELECT * FROM deliveries
        WHERE status = 'IN_PROGRESS'
          AND lease_expires_at IS NOT NULL
          AND lease_expires_at <= ?
        ORDER BY lease_expires_at ASC, created_at ASC, id ASC
        LIMIT ?
        """,
        (rs, row) -> delivery(rs),
        Timestamp.from(now),
        limit);
  }

  public List<DeliveryAttempt> attempts(UUID projectId, UUID id, int limit, long offset) {
    checkPage(limit, offset);
    return jdbc.query(
        """
        SELECT * FROM delivery_attempts WHERE project_id = ? AND delivery_id = ?
        ORDER BY attempt_number LIMIT ? OFFSET ?
        """,
        (rs, row) -> attempt(rs),
        projectId,
        id,
        limit,
        offset);
  }

  public List<DeliveryCycle> cycles(UUID projectId, UUID id, int limit, long offset) {
    checkPage(limit, offset);
    return jdbc.query(
        """
        SELECT * FROM delivery_cycles WHERE project_id = ? AND delivery_id = ?
        ORDER BY cycle_number LIMIT ? OFFSET ?
        """,
        (rs, row) ->
            DeliveryCycle.builder()
                .deliveryId(id)
                .number(rs.getInt("cycle_number"))
                .startedAt(instant(rs, "started_at"))
                .deadline(instant(rs, "deadline"))
                .policyVersion(rs.getString("policy_version"))
                .maxAttempts(rs.getInt("max_attempts"))
                .terminalStatus(
                    rs.getString("terminal_status") == null
                        ? null
                        : DeliveryStatus.valueOf(rs.getString("terminal_status")))
                .terminalAt(instant(rs, "terminal_at"))
                .build(),
        projectId,
        id,
        limit,
        offset);
  }

  /**
   * Future dispatcher must perform fleet admission within this same transaction before claiming.
   */
  public Optional<Delivery> claim(UUID projectId, UUID id, String owner, Duration lease) {
    if (owner == null
        || !owner.matches("[A-Za-z0-9._:-]{1,80}")
        || lease == null
        || lease.compareTo(Duration.ofSeconds(1)) < 0
        || lease.compareTo(Duration.ofMinutes(5)) > 0
        || lease.getNano() != 0) throw new IllegalArgumentException("Invalid delivery lease");
    return transactions.execute(tx -> {
      var locked = lock(projectId, id);
      if (locked.isEmpty()) return Optional.empty();
      Delivery d = locked.get().delivery();
      Instant now = now();
      if (!locked.get().active()
          || (d.status() != DeliveryStatus.PENDING && d.status() != DeliveryStatus.RETRY_SCHEDULED)
          || d.nextAttemptAt().isAfter(now)
          || !d.cycleDeadline().isAfter(now)
          || d.cycleAttemptCount() >= d.maxAttempts()) return Optional.empty();
      d.status().requireTransitionTo(DeliveryStatus.IN_PROGRESS);
      jdbc.update(
          """
          UPDATE deliveries SET status='IN_PROGRESS', next_attempt_at=NULL, lease_owner=?,
            lease_token=?, lease_expires_at=?, updated_at=clock_timestamp()
          WHERE project_id=? AND id=? AND status=?
          """,
          owner,
          UUID.randomUUID(),
          Timestamp.from(now.plus(lease)),
          projectId,
          id,
          d.status().name());
      return find(projectId, id);
    });
  }

  public Optional<DeliveryAttempt> startAttempt(UUID projectId, UUID id, UUID token) {
    return transactions.execute(tx -> {
      var locked = lock(projectId, id);
      if (locked.isEmpty()) return Optional.empty();
      Delivery d = locked.get().delivery();
      Instant now = now();
      if (!locked.get().active()
          || !owns(d, token, now)
          || !d.cycleDeadline().isAfter(now)
          || d.cycleAttemptCount() >= d.maxAttempts()) return Optional.empty();
      if (jdbc.queryForObject(
              """
              SELECT count(*) FROM delivery_attempts WHERE project_id=? AND delivery_id=? AND outcome='STARTED'
              """,
              Integer.class,
              projectId,
              id)
          != 0) return Optional.empty();
      UUID attemptId = UUID.randomUUID();
      jdbc.update(
          """
          INSERT INTO delivery_attempts(id,project_id,delivery_id,attempt_number,cycle_number,
              lease_token,started_at,attempted_at,outcome)
          VALUES (?,?,?,?,?,?,?,?,'STARTED')
          """,
          attemptId,
          projectId,
          id,
          d.attemptCount() + 1,
          d.cycleNumber(),
          token,
          Timestamp.from(now),
          Timestamp.from(now));
      jdbc.update(
          """
          UPDATE deliveries SET attempt_count=attempt_count+1,cycle_attempt_count=cycle_attempt_count+1,
            updated_at=clock_timestamp() WHERE project_id=? AND id=? AND status='IN_PROGRESS' AND lease_token=?
          """,
          projectId,
          id,
          token);
      return jdbc
          .query(
              "SELECT * FROM delivery_attempts WHERE project_id=? AND delivery_id=? AND id=?",
              (rs, row) -> attempt(rs),
              projectId,
              id,
              attemptId)
          .stream()
          .findFirst();
    });
  }

  /** Stores a caller-classified result and due time; deliberately does not calculate retries. */
  public boolean finishAttempt(
      UUID projectId,
      UUID id,
      UUID token,
      UUID attemptId,
      AttemptOutcome outcome,
      Integer httpStatus,
      long durationMs,
      String errorCode,
      Instant retryAt) {
    outcome.validateResult(httpStatus, durationMs);
    if (outcome == AttemptOutcome.ABORTED)
      throw new IllegalArgumentException("Use cancellation for aborted attempts");
    checkError(errorCode);
    if (retryAt != null
        && outcome != AttemptOutcome.RETRYABLE_FAILURE
        && outcome != AttemptOutcome.UNKNOWN)
      throw new IllegalArgumentException("Only retryable or unknown results can be rescheduled");
    return transactions.execute(tx -> {
      var locked = lock(projectId, id);
      if (locked.isEmpty()) return false;
      Delivery d = locked.get().delivery();
      Instant now = now();
      if (!owns(d, token, now)) return false;
      var target =
          outcome == AttemptOutcome.SUCCEEDED
              ? DeliveryStatus.DELIVERED
              : retryTarget(d, retryAt, now);
      int changed =
          jdbc.update(
              """
              UPDATE delivery_attempts SET outcome=?, http_status=?, duration_ms=?, error_code=?, completed_at=?
              WHERE project_id=? AND delivery_id=? AND id=? AND lease_token=? AND outcome='STARTED'
              """,
              outcome.name(),
              httpStatus,
              durationMs,
              errorCode,
              Timestamp.from(now),
              projectId,
              id,
              attemptId,
              token);
      if (changed == 0) return false;
      transition(
          d, target, target == DeliveryStatus.RETRY_SCHEDULED ? retryAt : null, errorCode, now);
      return true;
    });
  }

  /**
   * Expires only the exact old claim. Recovery scheduling/classification is supplied by later code.
   */
  public boolean recoverExpired(UUID projectId, UUID id, UUID token, Instant retryAt) {
    return transactions.execute(tx -> {
      var locked = lock(projectId, id);
      if (locked.isEmpty()) return false;
      Delivery d = locked.get().delivery();
      Instant now = now();
      if (d.status() != DeliveryStatus.IN_PROGRESS
          || token == null
          || !token.equals(d.leaseToken())
          || d.leaseExpiresAt().isAfter(now)) return false;
      jdbc.update(
          """
          UPDATE delivery_attempts SET outcome='UNKNOWN', completed_at=?, error_code='LEASE_EXPIRED'
          WHERE project_id=? AND delivery_id=? AND lease_token=? AND outcome='STARTED'
          """,
          Timestamp.from(now),
          projectId,
          id,
          token);
      DeliveryStatus target = retryTarget(d, retryAt, now);
      transition(
          d,
          target,
          target == DeliveryStatus.RETRY_SCHEDULED ? retryAt : null,
          "LEASE_EXPIRED",
          now);
      return true;
    });
  }

  public boolean cancel(UUID projectId, UUID id, String reasonCode) {
    checkError(reasonCode);
    return transactions.execute(tx -> {
      var locked = lock(projectId, id);
      if (locked.isEmpty() || locked.get().delivery().status().terminal()) return false;
      Delivery d = locked.get().delivery();
      Instant now = now();
      jdbc.update(
          """
          UPDATE delivery_attempts SET outcome='ABORTED',completed_at=?,error_code=?
          WHERE project_id=? AND delivery_id=? AND outcome='STARTED'
          """,
          Timestamp.from(now),
          reasonCode,
          projectId,
          id);
      transition(d, DeliveryStatus.CANCELLED, null, reasonCode, now);
      return true;
    });
  }

  /** Expires queued work without inventing a transport attempt, including disabled endpoints. */
  public boolean expireHorizon(UUID projectId, UUID id) {
    return transactions.execute(tx -> {
      var locked = lock(projectId, id);
      if (locked.isEmpty()) return false;
      Delivery d = locked.get().delivery();
      Instant now = now();
      if ((d.status() != DeliveryStatus.PENDING && d.status() != DeliveryStatus.RETRY_SCHEDULED)
          || d.cycleDeadline().isAfter(now)) return false;
      transition(d, DeliveryStatus.DEAD_LETTERED, null, "RETRY_HORIZON_EXPIRED", now);
      return true;
    });
  }

  private DeliveryStatus retryTarget(Delivery d, Instant retryAt, Instant now) {
    if (retryAt == null
        || !d.cycleDeadline().isAfter(now)
        || d.cycleAttemptCount() >= d.maxAttempts()
        || retryAt.isAfter(d.cycleDeadline())) return DeliveryStatus.DEAD_LETTERED;
    if (retryAt.isBefore(now))
      throw new IllegalArgumentException("Retry time must not be in the past");
    return DeliveryStatus.RETRY_SCHEDULED;
  }

  private void transition(
      Delivery d, DeliveryStatus next, Instant dueAt, String error, Instant now) {
    d.status().requireTransitionTo(next);
    jdbc.update(
        """
        UPDATE deliveries SET status=?, next_attempt_at=?, terminal_at=?, last_error_code=?,
          lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,updated_at=?
        WHERE project_id=? AND id=? AND status=?
        """,
        next.name(),
        timestamp(dueAt),
        next.terminal() ? Timestamp.from(now) : null,
        error,
        Timestamp.from(now),
        d.projectId(),
        d.id(),
        d.status().name());
    if (next.terminal())
      jdbc.update(
          """
          UPDATE delivery_cycles SET terminal_status=?,terminal_at=?
          WHERE project_id=? AND delivery_id=? AND cycle_number=? AND terminal_status IS NULL
          """,
          next.name(),
          Timestamp.from(now),
          d.projectId(),
          d.id(),
          d.cycleNumber());
  }

  private Optional<LockedDelivery> lock(UUID projectId, UUID id) {
    var initial = find(projectId, id);
    if (initial.isEmpty()) return Optional.empty();
    UUID endpointId = initial.get().endpointId();
    jdbc.update(
        "INSERT INTO project_dispatch_controls(project_id) VALUES (?) ON CONFLICT DO NOTHING",
        projectId);
    jdbc.queryForObject(
        "SELECT project_id FROM project_dispatch_controls WHERE project_id=? FOR UPDATE",
        UUID.class,
        projectId);
    jdbc.update(
        """
        INSERT INTO endpoint_dispatch_controls(project_id,endpoint_id)
        SELECT project_id,id FROM endpoints WHERE project_id=? AND id=? ON CONFLICT DO NOTHING
        """,
        projectId,
        endpointId);
    jdbc.queryForObject(
        "SELECT endpoint_id FROM endpoint_dispatch_controls WHERE project_id=? AND endpoint_id=? FOR UPDATE",
        UUID.class,
        projectId,
        endpointId);
    boolean active =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                """
                SELECT enabled AND deleted_at IS NULL FROM endpoints WHERE project_id=? AND id=? FOR UPDATE
                """,
                Boolean.class,
                projectId,
                endpointId));
    return jdbc
        .query(
            "SELECT * FROM deliveries WHERE project_id=? AND id=? FOR UPDATE",
            (rs, row) -> new LockedDelivery(delivery(rs), active),
            projectId,
            id)
        .stream()
        .findFirst();
  }

  private Instant now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
  }

  private static boolean owns(Delivery d, UUID token, Instant now) {
    return d.status() == DeliveryStatus.IN_PROGRESS
        && token != null
        && token.equals(d.leaseToken())
        && d.leaseExpiresAt().isAfter(now);
  }

  private static void checkPage(int limit, long offset) {
    if (limit < 1 || limit > 100 || offset < 0)
      throw new IllegalArgumentException("Invalid pagination");
  }

  private static void checkError(String code) {
    if (code != null && !code.matches("[A-Z0-9_]{1,80}"))
      throw new IllegalArgumentException("Error must be a safe classification code");
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Delivery delivery(ResultSet rs) throws SQLException {
    return Delivery.builder()
        .id(rs.getObject("id", UUID.class))
        .projectId(rs.getObject("project_id", UUID.class))
        .eventId(rs.getObject("event_id", UUID.class))
        .endpointId(rs.getObject("endpoint_id", UUID.class))
        .destinationUrl(rs.getString("destination_url"))
        .status(DeliveryStatus.valueOf(rs.getString("status")))
        .attemptCount(rs.getInt("attempt_count"))
        .cycleNumber(rs.getInt("cycle_number"))
        .cycleAttemptCount(rs.getInt("cycle_attempt_count"))
        .nextAttemptAt(instant(rs, "next_attempt_at"))
        .leaseOwner(rs.getString("lease_owner"))
        .leaseToken(rs.getObject("lease_token", UUID.class))
        .leaseExpiresAt(instant(rs, "lease_expires_at"))
        .cycleStartedAt(instant(rs, "cycle_started_at"))
        .cycleDeadline(instant(rs, "cycle_deadline"))
        .policyVersion(rs.getString("policy_version"))
        .maxAttempts(rs.getInt("max_attempts"))
        .terminalAt(instant(rs, "terminal_at"))
        .lastErrorCode(rs.getString("last_error_code"))
        .createdAt(instant(rs, "created_at"))
        .updatedAt(instant(rs, "updated_at"))
        .build();
  }

  private static DeliveryAttempt attempt(ResultSet rs) throws SQLException {
    return DeliveryAttempt.builder()
        .id(rs.getObject("id", UUID.class))
        .projectId(rs.getObject("project_id", UUID.class))
        .deliveryId(rs.getObject("delivery_id", UUID.class))
        .attemptNumber(rs.getInt("attempt_number"))
        .cycleNumber(rs.getInt("cycle_number"))
        .leaseToken(rs.getObject("lease_token", UUID.class))
        .startedAt(instant(rs, "started_at"))
        .completedAt(instant(rs, "completed_at"))
        .outcome(AttemptOutcome.valueOf(rs.getString("outcome")))
        .httpStatus(rs.getObject("http_status", Integer.class))
        .durationMs(rs.getObject("duration_ms", Long.class))
        .errorCode(rs.getString("error_code"))
        .build();
  }

  private record LockedDelivery(Delivery delivery, boolean active) {}
}
