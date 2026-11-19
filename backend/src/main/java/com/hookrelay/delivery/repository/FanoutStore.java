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

/** Atomic, tenant-scoped fan-out persistence; no broker or network operations. */
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

  /** Bounded, project-scoped operator reconciliation; never republishes automatically. */
  public List<UUID> findPublishedWithoutSnapshot(UUID projectId, int limit) {
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("Invalid reconciliation limit");
    return jdbc.query(
        connection -> {
          var query =
              connection.prepareStatement(
                  """
                  SELECT e.id FROM events e WHERE e.project_id=?
                    AND EXISTS (SELECT 1 FROM outbox_messages o WHERE o.project_id=e.project_id
                      AND o.aggregate_id=e.id AND o.message_type='EVENT_INGESTED' AND o.status='PUBLISHED'
                      AND o.published_at<clock_timestamp()-interval '60 seconds')
                    AND NOT EXISTS (SELECT 1 FROM event_fanouts f WHERE f.project_id=e.project_id AND f.event_id=e.id)
                  ORDER BY e.created_at,e.id LIMIT ?
                  """);
          query.setObject(1, projectId);
          query.setInt(2, limit);
          query.setQueryTimeout(5);
          return query;
        },
        (rs, row) -> rs.getObject("id", UUID.class));
  }

  /** Internal explicit-recipient entry point retained for persistence callers. */
  public FanoutSnapshot create(
      UUID projectId, UUID eventId, List<UUID> recipientIds, byte[] body, DeliveryPolicy policy) {
    List<UUID> recipients = List.copyOf(recipientIds);
    if (recipients.size() > 1000 || new HashSet<>(recipients).size() != recipients.size())
      throw new IllegalArgumentException("Invalid fan-out snapshot");
    return create(
        projectId,
        eventId,
        body,
        policy,
        recipients.isEmpty() ? "false" : "e.id IN (:ids)",
        java.util.Map.of("ids", recipients),
        recipients.size());
  }

  /** Subscription eligibility and captured URLs come from the INSERT's single MVCC snapshot. */
  public FanoutSnapshot createSubscribed(
      UUID projectId, UUID eventId, String eventType, byte[] body, DeliveryPolicy policy) {
    return create(
        projectId,
        eventId,
        body,
        policy,
        """
        EXISTS (SELECT 1 FROM endpoint_subscriptions s WHERE s.endpoint_id=e.id
          AND s.event_type=:type AND s.deleted_at IS NULL)
        """,
        java.util.Map.of("type", eventType),
        null);
  }

  private FanoutSnapshot create(
      UUID projectId,
      UUID eventId,
      byte[] body,
      DeliveryPolicy policy,
      String selection,
      java.util.Map<String, ?> selectionParameters,
      Integer expectedCount) {
    byte[] immutableBody = body.clone();
    if (immutableBody.length < 2 || immutableBody.length > 270336)
      throw new IllegalArgumentException("Invalid fan-out body");
    java.util.Objects.requireNonNull(policy, "policy");
    return transactions.execute(tx -> {
      var events =
          jdbc.query(
              "SELECT created_at FROM events WHERE project_id=? AND id=? FOR UPDATE",
              (rs, row) -> rs.getTimestamp("created_at").toInstant(),
              projectId,
              eventId);
      if (events.isEmpty()) throw new IllegalArgumentException("Event not found");
      var existing = find(projectId, eventId);
      if (existing.isPresent()) return existing.get();
      if (jdbc.queryForObject(
              "SELECT count(*) FROM deliveries WHERE project_id=? AND event_id=?",
              Integer.class,
              projectId,
              eventId)
          != 0) throw new IllegalStateException("Incomplete existing fan-out requires review");
      Instant startedAt =
          jdbc.queryForObject("SELECT clock_timestamp()", java.time.OffsetDateTime.class)
              .toInstant();
      Instant deadline = startedAt.plus(policy.horizon());
      Instant retentionEnd = events.getFirst().plus(java.time.Duration.ofDays(30));
      if (deadline.isAfter(retentionEnd)) deadline = retentionEnd;
      if (!deadline.isAfter(startedAt))
        throw new IllegalStateException("Event retention window expired");
      var parameters =
          new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
              .addValues(selectionParameters)
              .addValue("project", projectId)
              .addValue("event", eventId)
              .addValue("started", Timestamp.from(startedAt))
              .addValue("deadline", Timestamp.from(deadline))
              .addValue("policy", policy.version())
              .addValue("attempts", policy.maxAttempts());
      // selection is an internal constant, never external SQL. 1001 is overflow detection,
      // not permission to commit a truncated recipient set.
      String sql =
          """
          WITH eligible AS MATERIALIZED (
            SELECT e.id,e.url FROM endpoints e WHERE e.project_id=:project
              AND e.enabled AND e.deleted_at IS NULL AND (%s) ORDER BY e.id LIMIT 1001
          ), inserted AS (
            INSERT INTO deliveries(id,project_id,event_id,endpoint_id,destination_url,status,
              attempt_count,next_attempt_at,cycle_number,cycle_attempt_count,cycle_started_at,
              cycle_deadline,policy_version,max_attempts)
            SELECT gen_random_uuid(),:project,:event,id,url,'PENDING',0,:started,0,0,
              :started,:deadline,:policy,:attempts FROM eligible
            WHERE (SELECT count(*) FROM eligible)<=1000 RETURNING id
          ), cycles AS (
            INSERT INTO delivery_cycles(project_id,delivery_id,cycle_number,started_at,deadline,policy_version,max_attempts)
            SELECT :project,id,0,:started,:deadline,:policy,:attempts FROM inserted RETURNING delivery_id
          )
          SELECT (SELECT count(*) FROM eligible) AS recipients,(SELECT count(*) FROM cycles) AS cycles
          """
              .formatted(selection);
      int count =
          new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
              .queryForObject(
                  sql,
                  parameters,
                  (rs, row) -> {
                    int recipients = rs.getInt("recipients");
                    if (recipients > 1000)
                      throw new IllegalStateException("Recipient capacity exceeded");
                    if (recipients != rs.getInt("cycles"))
                      throw new IllegalStateException("Incomplete fan-out");
                    return recipients;
                  });
      if (expectedCount != null && count != expectedCount)
        throw new IllegalArgumentException("Recipient not eligible");
      jdbc.update(
          """
          INSERT INTO event_fanouts(project_id,event_id,snapshot_at,recipient_count,body_version,body)
          VALUES (?,?,?,?,'v1',?)
          """,
          projectId,
          eventId,
          Timestamp.from(startedAt),
          count,
          immutableBody);
      return find(projectId, eventId).orElseThrow();
    });
  }
}
