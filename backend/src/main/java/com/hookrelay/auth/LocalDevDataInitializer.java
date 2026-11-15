package com.hookrelay.auth;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class LocalDevDataInitializer implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(LocalDevDataInitializer.class);
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID API_KEY_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final Pattern KEY_PATTERN =
      Pattern.compile("^hr_live_([A-Za-z0-9]{4,16})_([A-Za-z0-9_-]{16,200})$");

  private final JdbcTemplate jdbcTemplate;
  private final String rawApiKey;

  public LocalDevDataInitializer(
      JdbcTemplate jdbcTemplate, @Value("${hookrelay.local.dev-api-key}") String rawApiKey) {
    this.jdbcTemplate = jdbcTemplate;
    this.rawApiKey = rawApiKey;
  }

  @Override
  public void run(ApplicationArguments args) {
    Matcher matcher = KEY_PATTERN.matcher(rawApiKey);
    if (!matcher.matches()) {
      throw new IllegalStateException(
          "HOOKRELAY_DEV_API_KEY must match hr_live_<4-16 alphanumeric prefix>_<16-200 alphanumeric, underscore or hyphen secret>");
    }

    jdbcTemplate.update(
        """
        INSERT INTO organizations (id, name)
        VALUES (?, 'HookRelay Local Development')
        ON CONFLICT (id) DO NOTHING
        """,
        ORGANIZATION_ID);

    jdbcTemplate.update(
        """
        INSERT INTO projects (id, organization_id, name, environment)
        VALUES (?, ?, 'Local Sandbox', 'development')
        ON CONFLICT (id) DO NOTHING
        """,
        PROJECT_ID,
        ORGANIZATION_ID);

    jdbcTemplate.update(
        """
        INSERT INTO api_keys (id, project_id, name, key_prefix, key_hash)
        VALUES (?, ?, 'Local development key', ?, ?)
        ON CONFLICT (key_prefix) DO UPDATE
        SET key_hash = EXCLUDED.key_hash, revoked_at = NULL
        """,
        API_KEY_ID,
        PROJECT_ID,
        matcher.group(1),
        ApiKeyAuthService.sha256(rawApiKey));

    log.warn(
        "LOCAL PROFILE ACTIVE. Development API key prefix '{}' is registered; the secret is intentionally not logged.",
        matcher.group(1));
  }
}
