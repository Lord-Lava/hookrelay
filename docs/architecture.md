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

## ADR-008: Registration DNS policy and transaction boundaries

Endpoint URLs permit HTTPS on port 443, without credentials, fragments or IPv6 zone identifiers.
Literal parsing uses Java 25 `InetAddress.ofLiteral`; hostnames are resolved once and every answer
must pass the public-address policy. Special-use IPv4 ranges and non-global IPv6 ranges are denied.
DNS failures, mixed public/private answers, timeouts and resolver saturation fail closed with generic
client errors. Production resolution has four workers, a 16-request queue and a two-second caller
deadline. The platform resolver may ignore cancellation; bounded workers prevent unbounded growth.

Creation and URL updates perform DNS validation before opening their write transaction. The
registration policy is not a connection-time guarantee. There is no outbound delivery client yet;
Phase 3 must pin every connection to a validated address, preserve TLS hostname verification and
disable redirects. See the Java 25 InetAddress API for literal versus hostname resolution semantics:
https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/InetAddress.html

## ADR-009: Endpoint mutation serialization and URL identity

Endpoint metadata updates, enable/disable, rotation and subscription mutations serialize on the
project-scoped endpoint row. DNS work completes before acquiring this lock. Metadata SQL never
rewrites encryption columns; rotation updates only encryption fields. Subscription repository
writes also require an explicit project predicate. Concurrent PUT requests return one created
subscription and subsequent existing responses, without treating uniqueness violations as 500s.

URL identity normalizes HTTPS scheme, host case/trailing dot, default port and empty path, while
preserving path/query case and escaped bytes. V5 normalizes existing URLs and replaces the old
whole-URL case-insensitive index. Before a large deployment, inspect equivalent URLs and plan the
migration's table/index locks; a normalization collision aborts migration without deleting data.
Pagination uses 64-bit offsets and a UUID tie-breaker for equal creation timestamps.

## ADR-010: Signing configuration and security audits

Endpoint creation/rotation returns 503 when signing encryption is unavailable; ingestion remains
available. No new unsigned endpoint is created. V6 disables legacy unsigned endpoints; rotate a
secret before re-enabling them. Creation and rotation responses use `Cache-Control: no-store` and
secret-bearing result records redact their `toString` output.

The active encryption key/version are `hookrelay.signing.encryption-key` and `key-version`.
Retain previous keys under `hookrelay.signing.decryption-keys.<version>` in external secret
configuration (never Git). New writes use the active version, reads use the stored version.
Do not remove an old key until its ciphertext has been re-encrypted or the corresponding secrets
have been rotated, including backups. KMS/envelope encryption is still a production deployment
requirement; this implementation is a locally configured keyring, not KMS.

Secret rotation immediately replaces the webhook secret. It writes a minimal immutable
`endpoint_audit_events` row in the same transaction; no secret values are recorded. V6 moves
historical rotation outbox rows into that table. Outbox rows are now constrained to EVENT_INGESTED.
Previously published rotation messages may already be in a broker queue: drain/quarantine them
before enabling a future ingestion consumer. A migration cannot retract an already published message.
