package com.hookrelay.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ApiKeyAuthService {
  private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthService.class);
  private static final Pattern KEY_PATTERN =
      Pattern.compile("^hr_live_([A-Za-z0-9]{4,16})_([A-Za-z0-9_-]{16,200})$");

  private final JdbcTemplate jdbcTemplate;

  public ApiKeyAuthService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<ProjectIdentity> authenticate(String rawKey) {
    try {
      if (rawKey == null || rawKey.length() > 256) {
        log.debug("Auth failed: null or too long key");
        return Optional.empty();
      }

      Matcher matcher = KEY_PATTERN.matcher(rawKey);
      if (!matcher.matches()) {
        log.debug("Auth failed: key pattern mismatch");
        return Optional.empty();
      }

      String prefix = matcher.group(1);
      Optional<ApiKeyRecord> keyRecord =
          jdbcTemplate.query(
              """
              SELECT k.id, k.key_hash, p.id AS project_id, p.organization_id
              FROM api_keys k
              JOIN projects p ON p.id = k.project_id
              WHERE k.key_prefix = ? AND k.revoked_at IS NULL
              """,
              rs -> {
                if (!rs.next()) {
                  return Optional.empty();
                }
                return Optional.of(
                    new ApiKeyRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("key_hash").trim(),
                        new ProjectIdentity(
                            rs.getObject("organization_id", UUID.class),
                            rs.getObject("project_id", UUID.class))));
              },
              prefix);

      if (keyRecord.isEmpty()) {
        log.debug("Auth failed: key not found in database");
        return Optional.empty();
      }

      ApiKeyRecord record = keyRecord.get();
      String providedHash = sha256(rawKey);
      boolean matches =
          MessageDigest.isEqual(
              record.keyHash().getBytes(StandardCharsets.US_ASCII),
              providedHash.getBytes(StandardCharsets.US_ASCII));
      if (!matches) {
        log.debug("Auth failed: hash mismatch");
        return Optional.empty();
      }

      // Do not execute a second statement while the key lookup ResultSet is open.
      int updated =
          jdbcTemplate.update(
              "UPDATE api_keys SET last_used_at = now() WHERE id = ? AND project_id = ? AND revoked_at IS NULL",
              record.id(),
              record.identity().projectId());
      if (updated == 0) {
        log.warn("Failed to update last_used_at for api key {}", record.id());
        // Continue anyway - the key is valid, we just couldn't update the timestamp
      }
      return Optional.of(record.identity());
    } catch (Exception e) {
      log.error("Auth failed with unexpected exception: {}", e.getMessage(), e);
      return Optional.empty();
    }
  }

  public static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private record ApiKeyRecord(UUID id, String keyHash, ProjectIdentity identity) {}
}
