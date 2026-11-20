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
before enabling the opt-in ingestion consumer. A migration cannot retract an already published message.

## ADR-011: HTTP limits and management exposure

All `/v1/` requests authenticate and consume a project request allowance before their bodies are
read. The streaming body cap applies to every API method, including unknown-length/chunked bodies;
headers are capped at 8 KiB. The default allowance is 1,000 authenticated requests per 60-second
window, configurable through PROJECT_REQUESTS_PER_WINDOW and PROJECT_REQUEST_WINDOW_SECONDS.
A PostgreSQL row per project makes enforcement atomic across application instances. Rejected
requests return 429 and Retry-After. This fixed-window policy may allow a burst across window
boundaries; it is an admission limit, not a delivery concurrency limit. Edge/IP rate limits and
slow-client timeouts remain deployment controls for unauthenticated traffic.

Only Actuator health is exposed over HTTP by default. Do not enable metrics/info/Prometheus on the
public listener. A deployment that needs scraping must explicitly provision a private authenticated
management listener or proxy. Project API keys are not management credentials.

HTTP regression tests use a real embedded server and JDK HTTP client, including a
non-root context path, chunked bodies, revoked keys and concurrent quota exhaustion.
HookRelay's validation advice has order -1, ahead of Boot's default ProblemDetail
advice (order 0), so invalid fields retain the documented `errors` object. See
https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html.
OpenAPI contract tests parse the specification and compare controller operations,
DTO fields, request length constraints and local schema references.


## ADR-012: Delivery persistence foundation (Phase 3 Step 1)

V8–V11 extend the existing delivery/attempt tables with explicit project ownership,
lease tokens/expiry, captured destinations, cycle limits and attempt outcomes.
Composite foreign keys prevent cross-project references. Initial delivery uniqueness
remains `(event_id, endpoint_id)`; lifetime attempt numbers remain unique per delivery.
Cycle 0 is mandatory at commit through a deferred composite foreign key. Later replay
cycles have schema support for project-scoped replay keys and actor audit metadata;
there is no replay operation yet.

`delivery.domain` owns legal states and persisted values. `delivery.repository.FanoutStore`
uses an event row lock and one transaction to insert deliveries, initial cycles and
`event_fanouts`, including an empty recipient snapshot. Stored bytes and destination
URLs are never updated by these repositories. No signing secrets or response bodies
are stored. The marker is an insert-only application contract, not a PostgreSQL trigger
that prevents a database administrator from modifying rows.

Step 2 now selects active subscriptions in
the same event-locked transaction (see ADR-013); the snapshot is at first committed fan-out processing,
not ingestion. An existing marker returns its original bytes and recipients without
adding later subscribers. The explicit-recipient persistence entry point validates internal recipient IDs; the
consumer uses the set-based subscription selection described below.

`DeliveryStore` provides scoped, bounded reads and guarded claim/start/completion,
lease-expiry recovery, cancellation and horizon expiry. Mutation lock order is project
control, endpoint control, endpoint, delivery, attempt. Fresh tokens and database time
fence expired workers. A claim consumes no attempt; STARTED increments lifetime and
cycle counts atomically. Completion updates attempt, delivery and cycle in one transaction.
Recovery marks an unfinished attempt UNKNOWN without inventing an HTTP outcome.
Terminal delivery references survive endpoint soft deletion.

Dispatch control rows and due/expiry indexes are foundations, not admission enforcement
or a scheduler. No caller initiates delivery work yet. Callers must supply classified
outcomes and due times; HTTP classification, backoff, fleet quotas, polling, endpoint
reconciliation and audited replay are later steps. A queued expired horizon can be
terminalized without creating a transport attempt, even while its endpoint is disabled.

V8 stops on unexpected preexisting delivery history because historical destinations
cannot be inferred from current endpoints. The empty-history V7 upgrade preserves
existing events/endpoints; V10 enforces the new invariants. Review populated-table DDL
locks before deployment. Details and actual test outcomes are in
[Step 1 verification](PHASE_3_STEP_1_VERIFICATION.md); the
[Phase 3 prompts and architecture reference](PHASE_3_WEBHOOK_DELIVERY_ENGINE.md) remain
intact for subsequent implementation.


## ADR-013: Transactional fan-out and manual broker acknowledgement (Phase 3 Step 2)

`EventNotificationParser` validates the existing five-field JSON envelope and outbox
message ID, rejects bodies over 1 MiB before parsing, duplicate keys, trailing JSON,
unknown fields and invalid metadata. `EventFanoutService` verifies scoped outbox
ownership/type/aggregate, locks the event and checks its type/time. It ignores broker
payload values for delivery construction and freezes body v1 from PostgreSQL:
`eventId`, `eventType`, `createdAt`, `data`; internal `projectId` is omitted.

The service owns a REQUIRES_NEW READ COMMITTED transaction with a 10-second timeout,
2-second lock timeout and 10-second SQL statement timeout. `FanoutStore` uses one
materialized recipient-selection/INSERT statement to capture exact-match active
subscriptions on enabled, nondeleted endpoints in the event's project. Deliveries,
initial cycles and the marker/body commit atomically. Concurrent edits after the
selection statement do not alter this snapshot, even before commit. Event locking
and database pair uniqueness make redelivery idempotent; empty snapshots remain final.
The set-based path rejects overflow above 1,000 recipients without partial inserts.
Endpoint creation locks its project before checking the 1,000-nondeleted-endpoint cap.
Deletion frees capacity; disabled endpoints still count. Overflow creation returns 409.

`FanoutConsumer` owns a Spring AMQP MANUAL container (default two consumers, prefetch
ten each). It calls `basicAck(tag,false)` only after the service returns from commit.
There is no listener-side HTTP, DNS or broker call inside the database transaction.
Malformed, foreign, historical and deterministic invariant failures are rejected once
without requeue to the existing internal broker DLQ. This creates no customer DLQ entry.

Transient connection/resource/transaction failures, lock timeouts and deadlocks pause
the container. A single bounded supervisor closes consumers, leaving failed work
unacknowledged for broker redelivery, and restarts after exponential 1–60-second delay
with jitter. PostgreSQL SQLSTATE checks cover failures Spring leaves uncategorized,
including lock timeout, shutdown and insufficient resources. No database transaction
or listener thread sleeps for backoff. Successful ACK resets recovery backoff. A crash
between commit and ACK can redeliver, but the committed marker prevents new recipients.
Bounded `hookrelay.fanout.messages{outcome=...}` counters report results without tenant,
URL or event labels; diagnostics omit payloads and SQL exception messages.

Fan-out is disabled by default and uses its own validated `hookrelay.fanout` properties.
Activation requires both a reviewed-rollout flag and an explicit acceptance cutoff.
Unprocessed older events are quarantined, not silently suppressed with zero-recipient
markers. Broker byte/overflow policies, legacy-message review and backlog decisions
remain deployment gates. `findPublishedWithoutSnapshot(projectId,limit)` provides
bounded operator reconciliation (60-second grace, at most 100 IDs, five-second query
limit); there is no automatic republish or retention job. No new schema migration or
dependency was required; V8–V11 supply the persistence invariants. See
[Step 2 rollout and verification](PHASE_3_STEP_2_VERIFICATION.md).
