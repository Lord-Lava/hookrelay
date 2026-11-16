package com.hookrelay.endpoint.repository;

import com.hookrelay.endpoint.domain.EndpointSubscription;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EndpointSubscriptionRepository {

  private final JdbcTemplate jdbcTemplate;

  public EndpointSubscriptionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<EndpointSubscription> findByIdAndEndpointIdAndProject(
      UUID id, UUID endpointId, UUID projectId) {
    return jdbcTemplate.query(
        """
        SELECT s.id, s.endpoint_id, s.event_type, s.created_at, s.deleted_at
        FROM endpoint_subscriptions s
        JOIN endpoints e ON e.id = s.endpoint_id
        WHERE s.id = ? AND s.endpoint_id = ? AND e.project_id = ?
          AND s.deleted_at IS NULL AND e.deleted_at IS NULL
        """,
        rs -> {
          if (!rs.next()) {
            return Optional.empty();
          }
          return Optional.of(mapRow(rs));
        },
        id,
        endpointId,
        projectId);
  }

  public List<EndpointSubscription> findByEndpointIdAndProject(UUID endpointId, UUID projectId) {
    return jdbcTemplate.query(
        """
        SELECT s.id, s.endpoint_id, s.event_type, s.created_at, s.deleted_at
        FROM endpoint_subscriptions s
        JOIN endpoints e ON e.id = s.endpoint_id
        WHERE s.endpoint_id = ? AND e.project_id = ?
          AND s.deleted_at IS NULL AND e.deleted_at IS NULL
        ORDER BY s.created_at DESC, s.id DESC
        """,
        rs -> {
          List<EndpointSubscription> subscriptions = new ArrayList<>();
          while (rs.next()) {
            subscriptions.add(mapRow(rs));
          }
          return subscriptions;
        },
        endpointId,
        projectId);
  }

  public Optional<EndpointSubscription> findByEndpointIdAndEventTypeAndProject(
      UUID endpointId, String eventType, UUID projectId) {
    return jdbcTemplate.query(
        """
        SELECT s.id, s.endpoint_id, s.event_type, s.created_at, s.deleted_at
        FROM endpoint_subscriptions s
        JOIN endpoints e ON e.id = s.endpoint_id
        WHERE s.endpoint_id = ? AND s.event_type = ? AND e.project_id = ?
          AND s.deleted_at IS NULL AND e.deleted_at IS NULL
        """,
        rs -> {
          if (!rs.next()) {
            return Optional.empty();
          }
          return Optional.of(mapRow(rs));
        },
        endpointId,
        eventType,
        projectId);
  }

  public EndpointSubscription save(EndpointSubscription subscription, UUID projectId) {
    if (subscription.id() == null) {
      return insert(subscription, projectId);
    }
    return update(subscription, projectId);
  }

  private EndpointSubscription insert(EndpointSubscription subscription, UUID projectId) {
    UUID id = UUID.randomUUID();
    var rows =
        jdbcTemplate.query(
            """
            INSERT INTO endpoint_subscriptions (id, endpoint_id, event_type, created_at)
            SELECT ?, e.id, ?, now() FROM endpoints e
            WHERE e.id = ? AND e.project_id = ? AND e.deleted_at IS NULL
            RETURNING id, endpoint_id, event_type, created_at, deleted_at
            """,
            (rs, rowNum) -> mapRow(rs),
            id,
            subscription.eventType(),
            subscription.endpointId(),
            projectId);
    if (rows.isEmpty())
      throw new IllegalStateException(
          "Cannot create subscription: endpoint not found or is soft-deleted");
    return rows.getFirst();
  }

  private EndpointSubscription update(EndpointSubscription subscription, UUID projectId) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE endpoint_subscriptions s
            SET event_type = ?
            FROM endpoints e
            WHERE s.id = ? AND s.endpoint_id = ? AND s.deleted_at IS NULL
              AND e.id = s.endpoint_id AND e.project_id = ? AND e.deleted_at IS NULL
            """,
            subscription.eventType(),
            subscription.id(),
            subscription.endpointId(),
            projectId);
    if (updated == 0) {
      throw new IllegalStateException(
          "Subscription was not found or was already soft-deleted: " + subscription.id());
    }
    return subscription;
  }

  public int softDeleteByEndpointIdAndProject(UUID endpointId, UUID projectId) {
    return jdbcTemplate.update(
        """
        UPDATE endpoint_subscriptions s
        SET deleted_at = now()
        FROM endpoints e
        WHERE e.id = s.endpoint_id AND s.endpoint_id = ? AND e.project_id = ?
          AND s.deleted_at IS NULL AND e.deleted_at IS NULL
        """,
        endpointId,
        projectId);
  }

  public int softDeleteByEndpointIdAndEventTypeAndProject(
      UUID endpointId, String eventType, UUID projectId) {
    return jdbcTemplate.update(
        """
        UPDATE endpoint_subscriptions s
        SET deleted_at = now()
        FROM endpoints e
        WHERE e.id = s.endpoint_id AND s.endpoint_id = ? AND s.event_type = ? AND e.project_id = ?
          AND s.deleted_at IS NULL AND e.deleted_at IS NULL
        """,
        endpointId,
        eventType,
        projectId);
  }

  public List<EndpointSubscription> findByEndpointIdAndProjectPaginated(
      UUID endpointId, UUID projectId, long offset, int limit) {
    return jdbcTemplate.query(
        """
        SELECT s.id, s.endpoint_id, s.event_type, s.created_at, s.deleted_at
        FROM endpoint_subscriptions s
        JOIN endpoints e ON e.id = s.endpoint_id
        WHERE s.endpoint_id = ? AND e.project_id = ?
          AND s.deleted_at IS NULL AND e.deleted_at IS NULL
        ORDER BY s.created_at DESC, s.id DESC
        LIMIT ? OFFSET ?
        """,
        rs -> {
          List<EndpointSubscription> subscriptions = new ArrayList<>();
          while (rs.next()) {
            subscriptions.add(mapRow(rs));
          }
          return subscriptions;
        },
        endpointId,
        projectId,
        limit,
        offset);
  }

  public int countByEndpointIdAndProject(UUID endpointId, UUID projectId) {
    return jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*)
        FROM endpoint_subscriptions s
        JOIN endpoints e ON e.id = s.endpoint_id
        WHERE s.endpoint_id = ? AND e.project_id = ?
          AND s.deleted_at IS NULL AND e.deleted_at IS NULL
        """,
        (rs, rowNum) -> rs.getInt(1),
        endpointId,
        projectId);
  }

  private EndpointSubscription mapRow(ResultSet rs) throws SQLException {
    return new EndpointSubscription(
        rs.getObject("id", UUID.class),
        rs.getObject("endpoint_id", UUID.class),
        rs.getString("event_type"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant());
  }
}
