package com.hookrelay.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ApiKeyAuthService {
  private static final Pattern KEY_PATTERN =
      Pattern.compile("^hr_live_([A-Za-z0-9]{4,16})_([A-Za-z0-9_-]{16,200})$");

  private final JdbcTemplate jdbcTemplate;

  public ApiKeyAuthService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<ProjectIdentity> authenticate(String rawKey) {
    if (rawKey == null || rawKey.length() > 256) {
      return Optional.empty();
    }

    Matcher matcher = KEY_PATTERN.matcher(rawKey);
    if (!matcher.matches()) {
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
      return Optional.empty();
    }

    ApiKeyRecord record = keyRecord.get();
    String providedHash = sha256(rawKey);
    boolean matches =
        MessageDigest.isEqual(
            record.keyHash().getBytes(StandardCharsets.US_ASCII),
            providedHash.getBytes(StandardCharsets.US_ASCII));
    if (!matches) {
      return Optional.empty();
    }

    // Do not execute a second statement while the key lookup ResultSet is open.
    int updated =
        jdbcTemplate.update(
            "UPDATE api_keys SET last_used_at = now() WHERE id = ? AND project_id = ? AND revoked_at IS NULL",
            record.id(),
            record.identity().projectId());
    if (updated == 0) return Optional.empty();
    return Optional.of(record.identity());
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
