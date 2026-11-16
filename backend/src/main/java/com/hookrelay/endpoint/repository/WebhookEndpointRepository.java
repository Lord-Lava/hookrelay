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
        SELECT id, project_id, url, description, signing_secret_ciphertext,
               signing_secret_nonce, signing_secret_key_version, enabled,
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

  public Optional<WebhookEndpoint> findForUpdateAndProject(UUID id, UUID projectId) {
    return jdbcTemplate.query(
        """
        SELECT id, project_id, url, description, signing_secret_ciphertext,
               signing_secret_nonce, signing_secret_key_version, enabled,
               created_at, updated_at, deleted_at
        FROM endpoints
        WHERE id = ? AND project_id = ? AND deleted_at IS NULL FOR UPDATE
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

  public List<WebhookEndpoint> findByProjectPaginated(UUID projectId, long offset, int limit) {
    return jdbcTemplate.query(
        """
        SELECT id, project_id, url, description, signing_secret_ciphertext,
               signing_secret_nonce, signing_secret_key_version, enabled,
               created_at, updated_at, deleted_at
        FROM endpoints
        WHERE project_id = ? AND deleted_at IS NULL
        ORDER BY created_at DESC, id DESC
        LIMIT ? OFFSET ?
        """,
        rs -> {
          List<WebhookEndpoint> endpoints = new ArrayList<>();
          while (rs.next()) {
            endpoints.add(mapRow(rs));
          }
          return endpoints;
        },
        projectId,
        limit,
        offset);
  }

  public List<WebhookEndpoint> findByProject(UUID projectId) {
    return jdbcTemplate.query(
        """
        SELECT id, project_id, url, description, signing_secret_ciphertext,
               signing_secret_nonce, signing_secret_key_version, enabled,
               created_at, updated_at, deleted_at
        FROM endpoints
        WHERE project_id = ? AND deleted_at IS NULL
        ORDER BY created_at DESC, id DESC
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

  public int countByProject(UUID projectId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM endpoints WHERE project_id = ? AND deleted_at IS NULL",
        (rs, rowNum) -> rs.getInt(1),
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
        INSERT INTO endpoints (id, project_id, url, description, signing_secret_ciphertext,
                               signing_secret_nonce, signing_secret_key_version, enabled, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
        RETURNING id, project_id, url, description, signing_secret_ciphertext,
                  signing_secret_nonce, signing_secret_key_version, enabled,
                  created_at, updated_at, deleted_at
        """,
        (rs, rowNum) -> mapRow(rs),
        id,
        endpoint.projectId(),
        endpoint.url(),
        endpoint.description(),
        endpoint.signingSecretCiphertext(),
        endpoint.nonce(),
        endpoint.keyVersion(),
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
    // Re-read to pick up database-generated updated_at timestamp
    return findByIdAndProject(endpoint.id(), endpoint.projectId())
        .orElseThrow(
            () -> new IllegalStateException("Endpoint disappeared after update: " + endpoint.id()));
  }

  public WebhookEndpoint updateSigningSecret(
      UUID id, UUID projectId, String ciphertext, byte[] nonce, String keyVersion) {
    int changed =
        jdbcTemplate.update(
            """
            UPDATE endpoints SET signing_secret_ciphertext = ?, signing_secret_nonce = ?,
              signing_secret_key_version = ?, updated_at = now()
            WHERE id = ? AND project_id = ? AND deleted_at IS NULL
            """,
            ciphertext,
            nonce,
            keyVersion,
            id,
            projectId);
    if (changed == 0)
      throw new com.hookrelay.endpoint.application.EndpointNotFoundException("Endpoint not found");
    return findByIdAndProject(id, projectId).orElseThrow();
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
        rs.getBytes("signing_secret_nonce"),
        rs.getString("signing_secret_key_version"),
        rs.getBoolean("enabled"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant());
  }
}
