package com.hookrelay.endpoint.repository;

import com.hookrelay.endpoint.domain.WebhookEndpoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookEndpointRepository {

  private final JdbcTemplate jdbcTemplate;

  public WebhookEndpointRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<WebhookEndpoint> findByIdAndProject(UUID id, UUID projectId) {
    return jdbcTemplate.query(
        """
        SELECT id, project_id, url, description, signing_secret_ciphertext, enabled,
               created_at, updated_at, deleted_at
        FROM endpoints
        WHERE id = ? AND project_id = ? AND deleted_at IS NULL
        """,
        rs -> {
          if (!rs.next()) {
            return Optional.empty();
          }
          return Optional.of(mapRow(rs));
        },
        id,
        projectId);
  }

  public List<WebhookEndpoint> findByProject(UUID projectId) {
    return jdbcTemplate.query(
        """
        SELECT id, project_id, url, description, signing_secret_ciphertext, enabled,
               created_at, updated_at, deleted_at
        FROM endpoints
        WHERE project_id = ? AND deleted_at IS NULL
        ORDER BY created_at DESC
        """,
        rs -> {
          List<WebhookEndpoint> endpoints = new ArrayList<>();
          while (rs.next()) {
            endpoints.add(mapRow(rs));
          }
          return endpoints;
        },
        projectId);
  }

  public WebhookEndpoint save(WebhookEndpoint endpoint) {
    if (endpoint.id() == null) {
      return insert(endpoint);
    }
    return update(endpoint);
  }

  private WebhookEndpoint insert(WebhookEndpoint endpoint) {
    UUID id = UUID.randomUUID();
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO endpoints (id, project_id, url, description, signing_secret_ciphertext, enabled, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, now(), now())
        RETURNING id, project_id, url, description, signing_secret_ciphertext, enabled,
                  created_at, updated_at, deleted_at
        """,
        (rs, rowNum) -> mapRow(rs),
        id,
        endpoint.projectId(),
        endpoint.url(),
        endpoint.description(),
        endpoint.signingSecretCiphertext(),
        endpoint.enabled());
  }

  private WebhookEndpoint update(WebhookEndpoint endpoint) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE endpoints
            SET url = ?, description = ?, enabled = ?, updated_at = now()
            WHERE id = ? AND project_id = ? AND deleted_at IS NULL
            """,
            endpoint.url(),
            endpoint.description(),
            endpoint.enabled(),
            endpoint.id(),
            endpoint.projectId());
    if (updated == 0) {
      throw new IllegalStateException(
          "Endpoint was not found or was already soft-deleted: " + endpoint.id());
    }
    return endpoint;
  }

  public int softDelete(UUID id, UUID projectId) {
    return jdbcTemplate.update(
        """
        UPDATE endpoints
        SET deleted_at = now(), updated_at = now()
        WHERE id = ? AND project_id = ? AND deleted_at IS NULL
        """,
        id,
        projectId);
  }

  private WebhookEndpoint mapRow(ResultSet rs) throws SQLException {
    return new WebhookEndpoint(
        rs.getObject("id", UUID.class),
        rs.getObject("project_id", UUID.class),
        rs.getString("url"),
        rs.getString("description"),
        rs.getString("signing_secret_ciphertext"),
        rs.getBoolean("enabled"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant());
  }
}
