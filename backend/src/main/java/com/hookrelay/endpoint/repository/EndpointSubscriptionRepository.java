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
        ORDER BY s.created_at DESC
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

  public EndpointSubscription save(EndpointSubscription subscription) {
    if (subscription.id() == null) {
      return insert(subscription);
    }
    return update(subscription);
  }

  private EndpointSubscription insert(EndpointSubscription subscription) {
    // Verify the endpoint exists and is not soft-deleted before subscribing
    int endpointCount =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM endpoints
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, rowNum) -> rs.getInt(1),
            subscription.endpointId());
    if (endpointCount == 0) {
      throw new IllegalStateException(
          "Cannot create subscription: endpoint not found or is soft-deleted: "
              + subscription.endpointId());
    }

    UUID id = UUID.randomUUID();
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO endpoint_subscriptions (id, endpoint_id, event_type, created_at)
        VALUES (?, ?, ?, now())
        RETURNING id, endpoint_id, event_type, created_at, deleted_at
        """,
        (rs, rowNum) -> mapRow(rs),
        id,
        subscription.endpointId(),
        subscription.eventType());
  }

  private EndpointSubscription update(EndpointSubscription subscription) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE endpoint_subscriptions
            SET event_type = ?
            WHERE id = ? AND endpoint_id = ? AND deleted_at IS NULL
            """,
            subscription.eventType(),
            subscription.id(),
            subscription.endpointId());
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

  private EndpointSubscription mapRow(ResultSet rs) throws SQLException {
    return new EndpointSubscription(
        rs.getObject("id", UUID.class),
        rs.getObject("endpoint_id", UUID.class),
        rs.getString("event_type"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant());
  }
}
