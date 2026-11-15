# Architecture decisions

## ADR-001: Start as a modular monolith

The first backend is one Spring Boot deployable with domain packages. Separate deployment units are deferred until independent scaling or operational isolation is justified.

## ADR-002: PostgreSQL is the source of truth

Events, idempotency fingerprints, outbox state, endpoint configuration, delivery state, and attempt history are persisted in PostgreSQL. RabbitMQ transports work; it is not the only durable record of a customer event.

## ADR-003: Transactional outbox

The event row and outbox row are committed atomically. An outbox publisher claims one row at a time with `FOR UPDATE SKIP LOCKED`, sets an expiring lease, commits, publishes a persistent JSON AMQP message outside the transaction, waits for correlated publisher confirms, and marks the row published. A process crash after broker acceptance but before marking the row published can produce a duplicate. Consumers must deduplicate by stable event ID.

## ADR-004: At-least-once, not exactly-once

The product contract will be at-least-once webhook delivery. Exactly-once delivery across an external HTTP receiver cannot be guaranteed. HookRelay exposes stable event IDs and recommends idempotent receiver handling.

## ADR-005: Tenant identity comes from credentials

The ingestion API resolves project and organization from the API key. The caller cannot choose another tenant by sending an organization/project ID. All new tenant-owned queries must be scoped to the authenticated identity.

## ADR-006: Keep broker DLQ separate from product DLQ

The RabbitMQ dead-letter queue is for broker-level consumer rejection/expiry. The customer-visible delivery DLQ will be a PostgreSQL-backed domain feature that records final delivery failures, response metadata, replay state, and audit history.

## ADR-007: Runtime and framework baseline

Use Java 25 LTS, Spring Boot 4.1.2, Gradle 9.9.1, PostgreSQL 18.7, and RabbitMQ 4.4.0 (verified 2026-11-13). Compile and run with the same Java major version. Spring Boot's BOM owns the versions of Spring Framework, Jackson, Micrometer, Flyway, pgJDBC, and other managed dependencies. See `docs/stack-versions.md` for current patch-level evidence, build plugins, checksums, and official references. Update deliberately after compatibility review and all checks.
