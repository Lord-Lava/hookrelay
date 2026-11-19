package com.hookrelay.delivery.repository;

import com.hookrelay.delivery.domain.DeliveryPolicy;
import com.hookrelay.delivery.domain.FanoutSnapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Atomic persistence for an already-selected recipient set. No subscription selector or listener.
 */
@Repository
public class FanoutStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public FanoutStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  public Optional<FanoutSnapshot> find(UUID projectId, UUID eventId) {
    return jdbc
        .query(
            """
            SELECT * FROM event_fanouts WHERE project_id = ? AND event_id = ?
            """,
            (rs, row) ->
                new FanoutSnapshot(
                    projectId,
                    eventId,
                    rs.getTimestamp("snapshot_at").toInstant(),
                    rs.getInt("recipient_count"),
                    rs.getString("body_version"),
                    rs.getBytes("body")),
            projectId,
            eventId)
        .stream()
        .findFirst();
  }

  /**
   * The future selector must use the same transaction and event lock as this persistence call.
   * Recipient IDs are internal input, not public authorization. Ownership is rechecked in SQL. Once
   * committed, even an empty snapshot is final; subsequent calls return the original bytes.
   */
  public FanoutSnapshot create(
      UUID projectId, UUID eventId, List<UUID> recipientIds, byte[] body, DeliveryPolicy policy) {
    List<UUID> recipients = List.copyOf(recipientIds);
    byte[] immutableBody = body.clone();
    if (immutableBody.length < 2
        || immutableBody.length > 270336
        || recipients.size() > 1000
        || new HashSet<>(recipients).size() != recipients.size()) {
      throw new IllegalArgumentException("Invalid fan-out snapshot");
    }
    java.util.Objects.requireNonNull(policy, "policy");
    return transactions.execute(tx -> {
      var events =
          jdbc.query(
              """
              SELECT created_at FROM events
              WHERE project_id = ? AND id = ? FOR UPDATE
              """,
              (rs, row) -> rs.getTimestamp("created_at").toInstant(),
              projectId,
              eventId);
      if (events.isEmpty()) throw new IllegalArgumentException("Event not found");
      var existing = find(projectId, eventId);
      if (existing.isPresent()) return existing.get();
      if (jdbc.queryForObject(
              "SELECT count(*) FROM deliveries WHERE project_id = ? AND event_id = ?",
              Integer.class,
              projectId,
              eventId)
          != 0) throw new IllegalStateException("Incomplete existing fan-out requires review");
      // Read time after acquiring the event lock; a contended lock can wait.
      Instant startedAt =
          jdbc.queryForObject("SELECT clock_timestamp()", java.time.OffsetDateTime.class)
              .toInstant();
      Instant deadline = startedAt.plus(policy.horizon());
      Instant retentionEnd = events.getFirst().plus(java.time.Duration.ofDays(30));
      if (deadline.isAfter(retentionEnd)) deadline = retentionEnd;
      if (!deadline.isAfter(startedAt))
        throw new IllegalStateException("Event retention window expired");
      // One statement snapshots endpoint URLs/state. Subscription selection is deliberately Step 2.
      UUID[] ids = recipients.toArray(UUID[]::new);
      List<Recipient> selected =
          jdbc.query(
              connection -> {
                var statement =
                    connection.prepareStatement(
                        """
                        SELECT id, url FROM endpoints
                        WHERE project_id = ? AND id = ANY (?) AND deleted_at IS NULL AND enabled = true
                        ORDER BY id
                        """);
                statement.setObject(1, projectId);
                statement.setArray(2, connection.createArrayOf("uuid", ids));
                return statement;
              },
              (rs, row) -> new Recipient(rs.getObject("id", UUID.class), rs.getString("url")));
      if (selected.size() != recipients.size())
        throw new IllegalArgumentException("Recipient not eligible");
      for (Recipient recipient : selected) {
        UUID deliveryId = UUID.randomUUID();
        jdbc.update(
            """
            INSERT INTO deliveries(id,project_id,event_id,endpoint_id,destination_url,status,
              attempt_count,next_attempt_at,cycle_number,cycle_attempt_count,cycle_started_at,
              cycle_deadline,policy_version,max_attempts)
            VALUES (?,?,?,?,?,'PENDING',0,?,0,0,?,?,?,?)
            """,
            deliveryId,
            projectId,
            eventId,
            recipient.id(),
            recipient.url(),
            Timestamp.from(startedAt),
            Timestamp.from(startedAt),
            Timestamp.from(deadline),
            policy.version(),
            policy.maxAttempts());
        jdbc.update(
            """
            INSERT INTO delivery_cycles(project_id,delivery_id,cycle_number,started_at,deadline,policy_version,max_attempts)
            VALUES (?,?,0,?,?,?,?)
            """,
            projectId,
            deliveryId,
            Timestamp.from(startedAt),
            Timestamp.from(deadline),
            policy.version(),
            policy.maxAttempts());
      }
      jdbc.update(
          """
          INSERT INTO event_fanouts(project_id,event_id,snapshot_at,recipient_count,body_version,body)
          VALUES (?,?,?,?,'v1',?)
          """,
          projectId,
          eventId,
          Timestamp.from(startedAt),
          selected.size(),
          immutableBody);
      return find(projectId, eventId).orElseThrow();
    });
  }

  private record Recipient(UUID id, String url) {}
}
