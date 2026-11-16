package com.hookrelay.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class SchemaUpgradeIntegrationTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.7-alpine");

  @Test
  void upgradesV4WithoutLosingAuditHistoryOrEnablingUnsignedEndpoints() {
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(source).target("4").load().migrate();
    var jdbc = new JdbcTemplate(source);
    UUID org = UUID.randomUUID(),
        project = UUID.randomUUID(),
        endpoint = UUID.randomUUID(),
        audit = UUID.randomUUID();
    jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'upgrade')", org);
    jdbc.update(
        "INSERT INTO projects(id,organization_id,name,environment) VALUES (?,?,'upgrade','development')",
        project,
        org);
    jdbc.update(
        "INSERT INTO endpoints(id,project_id,url,signing_secret_ciphertext) VALUES (?,?,'HTTPS://Example.COM:443/Hook','')",
        endpoint,
        project);
    jdbc.update(
        "INSERT INTO outbox_messages(id,project_id,aggregate_id,message_type,payload) VALUES (?,?,?,'SECRET_ROTATED','{}')",
        audit,
        project,
        endpoint);
    Flyway.configure().dataSource(source).load().migrate();
    assertThat(
            jdbc.queryForObject(
                "SELECT url FROM endpoints WHERE id=? AND project_id=?",
                String.class,
                endpoint,
                project))
        .isEqualTo("https://example.com/Hook");
    assertThat(
            jdbc.queryForObject(
                "SELECT enabled FROM endpoints WHERE id=? AND project_id=?",
                Boolean.class,
                endpoint,
                project))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_audit_events WHERE id=? AND project_id=?",
                Integer.class,
                audit,
                project))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_messages WHERE project_id=?", Integer.class, project))
        .isZero();
  }
}
