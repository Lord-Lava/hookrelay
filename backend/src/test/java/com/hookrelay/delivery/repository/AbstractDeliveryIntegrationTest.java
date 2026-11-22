package com.hookrelay.delivery.repository;

import com.hookrelay.delivery.application.RetryScheduler;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared PostgreSQL test harness for delivery integration tests.
 *
 * <p>Centralises the DataSource/JdbcTemplate/TransactionTemplate/Flyway schema bootstrap, the
 * {@link DeliveryStore} construction, and the organisation/project/event seed data so the migration
 * target and seed schema are defined in one place instead of duplicated across test classes.
 * Subclasses supply endpoint-specific seeding and any mocks or helper services they need.
 */
@Testcontainers
public abstract class AbstractDeliveryIntegrationTest {

  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.7-alpine");

  protected DriverManagerDataSource source;
  protected JdbcTemplate jdbc;
  protected TransactionTemplate tx;
  protected DeliveryStore deliveries;
  protected final RetryScheduler retryScheduler =
      new RetryScheduler(10, 3600, Duration.ofHours(24));
  protected String schema;
  protected UUID project;
  protected UUID endpoint;
  protected UUID event;
  protected UUID organizationId;

  @BeforeEach
  protected void setUp() {
    schema = "delivery_" + UUID.randomUUID().toString().replace("-", "");
    source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema="
                + schema,
            postgres.getUsername(),
            postgres.getPassword());
    jdbc = new JdbcTemplate(source);
    // Schema is generated locally, never derived from external input.
    jdbc.execute("CREATE SCHEMA " + schema);
    migrate("12");
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    deliveries = new DeliveryStore(jdbc, tx, retryScheduler);
    UUID org = UUID.randomUUID();
    organizationId = org;
    project = UUID.randomUUID();
    endpoint = UUID.randomUUID();
    event = UUID.randomUUID();
    seedOrganization(org);
    seedProject(project, org, project.toString());
    seedEvent(event, project);
  }

  @AfterEach
  void cleanUp() {
    if (jdbc != null) {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  protected void seedOrganization(UUID org) {
    jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'test')", org);
  }

  protected void seedProject(UUID id, UUID org, String name) {
    jdbc.update(
        "INSERT INTO projects(id,organization_id,name,environment) VALUES (?,?,?,'development')",
        id,
        org,
        name);
  }

  protected void seedEvent(UUID id, UUID projectId) {
    jdbc.update(
        "INSERT INTO events(id,project_id,event_type,payload,request_fingerprint) VALUES (?,?,'test.created','{}',?)",
        id,
        projectId,
        "a".repeat(64));
  }

  protected void migrate(String target) {
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
        .migrate();
  }
}
