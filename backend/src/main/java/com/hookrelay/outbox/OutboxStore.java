package com.hookrelay.outbox;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class OutboxStore {
  private static final String CLAIM_NEXT_SQL =
      """
      WITH candidate AS (
          SELECT id
          FROM outbox_messages
          WHERE (status = 'PENDING' AND available_at <= now())
             OR (status = 'IN_FLIGHT' AND locked_until <= now())
          ORDER BY created_at
          LIMIT 1
          FOR UPDATE SKIP LOCKED
      )
      UPDATE outbox_messages AS o
      SET status = 'IN_FLIGHT',
          locked_by = ?,
          locked_until = now() + (? * interval '1 second'),
          publish_attempts = publish_attempts + 1
      FROM candidate c
      WHERE o.id = c.id
      RETURNING o.id, o.payload::text AS payload_json, o.publish_attempts
      """;

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  public OutboxStore(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
  }

  public Optional<OutboxMessage> claimNext(String lockOwner, int leaseSeconds) {
    return transactionTemplate.execute(status ->
        jdbcTemplate.query(
            CLAIM_NEXT_SQL,
            rs -> {
              if (!rs.next()) {
                return Optional.empty();
              }
              return Optional.of(
                  new OutboxMessage(
                      rs.getObject("id", UUID.class),
                      rs.getString("payload_json"),
                      rs.getInt("publish_attempts")));
            },
            lockOwner,
            leaseSeconds));
  }

  public void markPublished(UUID id, String lockOwner) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE outbox_messages
            SET status = 'PUBLISHED', published_at = now(), locked_by = NULL,
                locked_until = NULL, last_error = NULL
            WHERE id = ? AND status = 'IN_FLIGHT' AND locked_by = ?
            """,
            id,
            lockOwner);
    if (updated == 0) {
      throw new IllegalStateException(
          "Outbox lease was lost before the message could be marked published: " + id);
    }
  }

  public void releaseForRetry(UUID id, String lockOwner, long delaySeconds, String errorCode) {
    String safeErrorCode =
        errorCode == null
            ? "publisher_error"
            : errorCode.substring(0, Math.min(errorCode.length(), 100));
    jdbcTemplate.update(
        """
        UPDATE outbox_messages
        SET status = 'PENDING', available_at = now() + (? * interval '1 second'),
            locked_by = NULL, locked_until = NULL, last_error = ?
        WHERE id = ? AND status = 'IN_FLIGHT' AND locked_by = ?
        """,
        delaySeconds,
        safeErrorCode,
        id,
        lockOwner);
  }
}
