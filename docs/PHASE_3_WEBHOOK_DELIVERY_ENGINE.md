# HookRelay — Phase 3: Reliable Webhook Delivery Engine

## Objective

Build the complete outbound webhook delivery system for HookRelay using the existing Java/Spring Boot, PostgreSQL and RabbitMQ infrastructure.

A customer must be able to publish an event to HookRelay and have that event reliably delivered as an authenticated HTTPS POST to every eligible subscribed endpoint.

The system must support durable delivery state, HMAC signatures, at-least-once delivery, retry scheduling, dead-lettering, manual replay, observability and tenant isolation.

**Phase 3 explicitly excludes the Next.js dashboard, payments, subscriptions/billing, SDK development, and production deployment.**

### Existing foundation

The previous implementation reports indicate that HookRelay already has:

- Project-scoped API-key authentication.
- Event ingestion with idempotency.
- PostgreSQL event persistence.
- Transactional outbox publisher.
- RabbitMQ publishing with publisher confirms.
- Webhook endpoint CRUD and subscriptions.
- Signing-secret encryption and rotation.
- Registration-time SSRF validation.
- Tenant isolation, API rate limiting and request-size limits.

The coding agent must verify these capabilities against the actual repository rather than treating this list as proof of implementation.

### Architecture to implement

```text
Client Application
       |
       v
Event Ingestion API
       |
       v
PostgreSQL
  events + outbox
       |
       v
Existing OutboxPublisher
       |
       v
RabbitMQ: EVENT_INGESTED
       |
       v
Event Consumer
       |
       v
Subscription Fan-out
       |
       v
PostgreSQL: deliveries
       |
       v
Delivery Dispatcher
       |
       v
SSRF-Safe HTTPS Client
       |
       v
Customer Webhook Endpoint
       |
       +-- 2xx --> DELIVERED
       |
       +-- Transient failure --> RETRY_SCHEDULED
       |
       +-- Permanent/exhausted --> DEAD_LETTERED
       |
       +-- Manual replay --> PENDING
```

PostgreSQL remains the source of truth for delivery status, attempt history and scheduled retries.

RabbitMQ handles event-consumer work distribution. The delivery dispatcher initially polls PostgreSQL for due work rather than introducing a second RabbitMQ delivery-job queue.

All asynchronous processing provides at-least-once semantics.

---

# Step 0 — Architecture Inspection and Design Decisions

### Goal

Understand the existing code and establish Phase 3 contracts before modifying production functionality.

### Coding-agent prompt

> Act as a principal backend architect preparing HookRelay for Phase 3.
>
> Read `AGENTS.md`, the root-level `HOOKRELAY_OVERVIEW.md`, `docs/architecture.md`, `docs/openapi.yaml`, and all relevant existing application code.
>
> Inspect the existing event ingestion API, transactional outbox, RabbitMQ publisher, authentication, endpoint repositories, subscription logic, signing-secret encryption and SSRF policy.
>
> Document the actual implemented architecture, including database schema, event message format, queue topology, acknowledgements, configuration and package boundaries.
>
> Decide and document:
>
> 1. Delivery states and legal transitions.
> 2. Delivery uniqueness and idempotent fan-out rules.
> 3. Subscription selection semantics. For the MVP, prefer a snapshot of eligible active subscriptions at the first committed fan-out transaction. Clearly document that this snapshot occurs at fan-out processing time, not necessarily at ingestion time.
> 4. PostgreSQL-based delivery job scheduling.
> 5. Lease expiry and crash recovery.
> 6. HTTP timeout and retry policies.
> 7. Customer-visible DLQ versus RabbitMQ's internal dead-letter queue.
> 8. Webhook signing protocol and rotation semantics.
> 9. Endpoint deletion/disable behaviour.
> 10. Tenant isolation and data retention.
>
> Identify all existing code that can be reused and all necessary forward-only migrations.
>
> Review current official documentation before adding dependencies or using framework APIs. Preserve compatible versions and existing architectural conventions.
>
> Create `docs/PHASE_3_DELIVERY_ENGINE.md` containing the architecture, decisions, risks and phased implementation plan.
>
> Do not implement the delivery engine in this step.
>
> Report important design uncertainties and any blockers.

**Acceptance:** The plan is reconciled with the actual repository, and the implementation approach is documented without introducing functional changes.

---

# Step 1 — Delivery Domain, State Machine and Persistence

### Goal

Create the durable foundation for individual webhook deliveries and attempts.

### Proposed delivery states

| State             | Meaning                                               |
| ----------------- | ----------------------------------------------------- |
| `PENDING`         | Created, awaiting first attempt                       |
| `IN_PROGRESS`     | Claimed by a delivery worker                          |
| `RETRY_SCHEDULED` | Waiting for the next attempt                          |
| `DELIVERED`       | Remote endpoint returned a successful response        |
| `DEAD_LETTERED`   | Delivery permanently failed or exhausted retry policy |
| `CANCELLED`       | Delivery intentionally stopped                        |

### Proposed persistence concepts

**`webhook_deliveries`**

- `id`
- `project_id`
- `event_id`
- `endpoint_id`
- `status`
- `attempt_count`
- `next_attempt_at`
- `lease_owner`
- `lease_token`
- `lease_expires_at`
- `last_error_code`
- `delivered_at`
- `created_at`
- `updated_at`

**`webhook_delivery_attempts`**

- `id`
- `delivery_id`
- `attempt_number`
- `started_at`
- `completed_at`
- `http_status`
- `duration_ms`
- `error_category`
- `response_preview` (bounded and sanitized)
- `outcome`

Include constraints, indexes, foreign keys and retention considerations. Preserve the existing migration history.

### Coding-agent prompt

> Implement Phase 3 Step 1: delivery domain modelling and persistence.
>
> Read `AGENTS.md`, root `HOOKRELAY_OVERVIEW.md` and `docs/PHASE_3_DELIVERY_ENGINE.md`. Inspect existing migrations, database conventions, and repository patterns.
>
> Implement the durable delivery and delivery-attempt models using the project's established JDBC/transaction approach.
>
> Requirements:
>
> - Explicit delivery state machine and valid transitions.
> - Exactly one initial delivery per event/endpoint pair, enforced by database uniqueness.
> - Tenant-scoped read and write operations.
> - Persisted attempt history.
> - Safe status transitions under concurrency.
> - Lease owner/token and expiry support for future workers.
> - Efficient indexes for due-delivery polling.
> - Database timestamps and appropriate constraints.
> - Preserve historical delivery references when endpoints are soft-deleted.
> - No plaintext signing secrets or sensitive response headers stored.
> - Forward-only Flyway migrations.
>
> Design a way to atomically record fan-out completion even when an event has zero recipients. This prevents a duplicate RabbitMQ message from creating deliveries for subscribers added later.
>
> Add unit and PostgreSQL Testcontainers integration tests covering constraints, lifecycle transitions, cross-tenant access, duplicate creation, concurrent updates and rollback.
>
> Do not implement RabbitMQ consumption, outbound HTTP requests or retries.
>
> Run `spotlessApply`, `clean check` and `bootJar`. Report actual results.

**Acceptance:** Delivery records and attempts are durable, tenant-safe and concurrency-safe.

---

# Step 2 — RabbitMQ Event Consumer and Subscription Fan-out

### Goal

Convert each successfully ingested event into individual endpoint delivery records.

### Example

```text
Event: payment.succeeded
         |
         v
RabbitMQ
         |
         v
Event Consumer
         |
         +--> Endpoint A: Delivery 1
         +--> Endpoint B: Delivery 2
         +--> Endpoint C: Delivery 3
```

Each destination must have independent delivery state and attempts.

### Coding-agent prompt

> Implement Phase 3 Step 2: RabbitMQ event consumption and subscription fan-out.
>
> Read all project agent and architecture instructions. Inspect the existing `EVENT_INGESTED` outbox message contract, RabbitMQ topology and endpoint-subscription repositories.
>
> Implement a Spring AMQP consumer that:
>
> 1. Consumes the existing event messages.
> 2. Resolves the trusted project/event identity from persisted data.
> 3. Queries eligible active subscriptions belonging to that project.
> 4. Creates independent, durable `PENDING` deliveries for eligible endpoints.
> 5. Records fan-out completion atomically with delivery creation.
> 6. Prevents duplicate fan-out on RabbitMQ redelivery.
> 7. Handles events with zero subscribed endpoints correctly.
> 8. Acknowledges the RabbitMQ message only after the database transaction commits.
>
> Do not make outbound HTTP requests from the RabbitMQ listener.
>
> Implement bounded consumer concurrency/prefetch and explicit handling for malformed or unprocessable messages. Avoid infinite immediate requeue loops. Use a bounded broker poison-message strategy with diagnostics.
>
> Preserve the distinction between the broker's operational DLQ and HookRelay's customer-visible delivery DLQ.
>
> Verify duplicate message delivery, consumer crashes, database failures, concurrent consumers, endpoint deletion, disabled endpoints and tenant isolation.
>
> Use RabbitMQ and PostgreSQL integration tests where infrastructure permits.
>
> Run all verification commands and document exact results.

**Acceptance:** One accepted event creates exactly one durable delivery for each eligible endpoint, regardless of duplicate consumer messages.

---

# Step 3 — SSRF-Safe Outbound HTTP Transport

### Goal

Create the secure HTTP client that will eventually send real webhooks.

This is the most security-sensitive step of Phase 3.

Registration-time SSRF validation is insufficient. The actual network connection must be made only to a validated destination.

### Coding-agent prompt

> Implement Phase 3 Step 3: a production-safe outbound webhook HTTP transport.
>
> Read the existing SSRF validator, DNS resolver, security ADRs, and project documentation.
>
> First, investigate the existing Java HTTP client options and verify which can safely bind outbound connections to validated DNS answers while preserving TLS hostname verification.
>
> Apache HttpClient 5 with a carefully configured custom DNS resolver/connection manager is an option, but do not assume its default configuration is sufficient.
>
> Requirements:
>
> 1. Accept only HTTPS destinations on permitted ports, retaining the current port 443 restriction.
> 2. Resolve and validate every DNS address at connection time.
> 3. Reject mixed globally reachable and prohibited DNS answers.
> 4. Ensure the actual socket connects to an approved address with no second uncontrolled DNS resolution.
> 5. Preserve original-hostname SNI and certificate hostname verification.
> 6. Never disable TLS certificate validation.
> 7. Disable redirects by default.
> 8. Prevent unexpected proxy configuration from bypassing IP enforcement.
> 9. Enforce connect, response and overall request timeouts.
> 10. Bound response-body consumption.
> 11. Reject unsupported schemes, invalid authorities and dangerous IP representations.
> 12. Use bounded connection pools and concurrency.
> 13. Release connections and other resources correctly.
>
> Introduce a testable transport interface so HTTP behaviour can be tested without weakening production SSRF policy.
>
> Add deterministic tests for DNS rebinding, mixed DNS answers, private IPs, IPv6, timeouts, redirects, oversized responses, invalid TLS certificates, hostname mismatches and attempted unvalidated connections.
>
> Demonstrate with integration tests that DNS validation and the actual connection use the same approved address.
>
> Do not implement delivery scheduling or retry logic yet.
>
> Run all build verification commands and report remaining network-level protections required for deployment.

**Acceptance:** The transport cannot connect to an unvalidated destination, and normal TLS hostname verification remains effective.

---

# Step 4 — HMAC Signing Protocol

### Goal

Ensure customers can verify that webhook requests genuinely came from HookRelay and were not modified.

### Recommended protocol

Define a versioned signature scheme that authenticates:

- Stable event identifier.
- Delivery identifier.
- Fresh timestamp.
- Exact raw HTTP request body.

Example headers:

```http
HookRelay-Event-Id: evt_123
HookRelay-Delivery-Id: del_456
HookRelay-Timestamp: 1790000000
HookRelay-Signature: v1=<hmac>
```

Document the exact byte-level canonical string before implementation. Generate a fresh timestamp and signature on each attempt. Standard Webhooks conventions are worth evaluating before finalizing a proprietary protocol.

### Coding-agent prompt

> Implement Phase 3 Step 4: versioned HMAC-SHA256 webhook request signing.
>
> Read existing signing-secret generation, encryption, decryption, rotation and key-version handling.
>
> Requirements:
>
> - Define and document the exact canonical signed payload.
> - Sign the actual raw request bytes, not reconstructed JSON.
> - Include event identity, delivery identity and timestamp in the authenticated material.
> - Produce a fresh timestamp/signature for every delivery attempt.
> - Decrypt endpoint secrets only when required.
> - Never log plaintext secrets or signed payload internals unnecessarily.
> - Preserve the existing encryption-key-version compatibility model.
> - Document how webhook signing-secret rotation affects retries and replay.
> - Prevent delivery from endpoints with unavailable or invalid signing secrets.
> - Provide a reference verification example for customers using constant-time signature comparison and a configurable timestamp freshness window.
>
> Prefer compatibility with an established versioned webhook signing convention where practical. Do not invent a superficially similar protocol without documenting its differences.
>
> Write deterministic tests using known HMAC test vectors, non-ASCII payloads, empty bodies, timestamp changes, modified payloads, wrong secrets and secret rotation.
>
> Integrate the signer with the outbound transport interface but do not start background delivery.
>
> Run all verification commands.

**Acceptance:** Webhook requests can be generated and independently verified using the documented signing protocol.

---

# Step 5 — Delivery Dispatcher, Worker and Lease Management

### Goal

Take persisted due deliveries and execute them safely and asynchronously.

### Recommended implementation

Use a scheduler to claim eligible delivery rows in bounded batches.

Conceptually:

```text
Scheduler wakes up
       |
       v
Find due deliveries
       |
       v
FOR UPDATE SKIP LOCKED
       |
       v
Persist worker lease
       |
       v
Commit claim transaction
       |
       v
Execute HTTPS request
       |
       v
Record attempt and outcome
```

Never hold a database transaction open during the external HTTP request.

### Coding-agent prompt

> Implement Phase 3 Step 5: durable delivery scheduling and outbound execution.
>
> Read the delivery persistence layer, secure HTTP transport and HMAC signer.
>
> Implement a configurable Spring-based dispatcher that periodically claims a bounded batch of eligible deliveries from PostgreSQL.
>
> Requirements:
>
> 1. Claim due deliveries atomically using `FOR UPDATE SKIP LOCKED` or an equivalent proven database mechanism.
> 2. Persist leases with unique ownership/fencing tokens.
> 3. Commit the claim before performing network I/O.
> 4. Use bounded worker concurrency and queues.
> 5. Recheck relevant endpoint state before starting transmission.
> 6. Build signed HTTPS requests using the previously implemented components.
> 7. Persist attempt start and terminal outcome.
> 8. Enforce legal state transitions.
> 9. Recover work abandoned by crashed workers.
> 10. Prevent stale workers from overwriting newer state.
> 11. Ensure timeout budgets and lease durations are consistent.
> 12. Support graceful shutdown.
>
> Document the unavoidable at-least-once duplicate-delivery window if a worker crashes after the receiver processes the request but before HookRelay commits the result.
>
> Do not claim exactly-once remote execution.
>
> Add tests for parallel workers, empty queues, lease expiry, worker crashes, slow endpoints, stale lease completion, duplicate claims, database unavailability and graceful shutdown.
>
> Run all verification commands.

**Acceptance:** A pending delivery reaches its destination, records its attempt, and cannot be permanently stranded by a worker crash.

---

# Step 6 — Retry Engine, Backoff and Persistent DLQ

### Goal

Automatically recover from temporary endpoint failures and preserve permanently failed deliveries.

### Suggested default policy

| Result                             | Behaviour                       |
|------------------------------------|---------------------------------|
| HTTP 2xx                           | Success                         |
| HTTP 408                           | Retry                           |
| HTTP 429                           | Retry; consider `Retry-After`   |
| HTTP 5xx                           | Retry                           |
| Connection timeout/network failure | Retry                           |
| Most other HTTP 4xx                | Permanent failure               |
| HTTP 3xx                           | Do not follow; treat as failure |
| Max attempts exhausted             | Dead-letter                     |

Use exponential backoff with jitter. Make retry attempts, maximum delay and total retry horizon configurable.

### Coding-agent prompt

> Implement Phase 3 Step 6: persisted retry scheduling and customer-visible dead-letter handling.
>
> Read the delivery state machine, worker implementation and current configuration.
>
> Implement a deterministic retry-policy component that classifies outcomes and calculates `next_attempt_at`.
>
> Requirements:
>
> - Retry transient network failures, timeouts, 408, 429 and appropriate 5xx responses.
> - Treat most other 4xx responses as permanent failures unless the documented policy explicitly differs.
> - Use exponential backoff with jitter.
> - Bound backoff, attempt count and total retry horizon.
> - Parse and cap `Retry-After` safely.
> - Persist retry schedules in PostgreSQL.
> - Never sleep worker threads waiting for retry times.
> - Track actual attempt history.
> - Transition exhausted or permanently failed deliveries into a persistent `DEAD_LETTERED` state.
> - Preserve failure reasons and sanitized response metadata.
> - Ensure terminal deliveries are not reclaimed automatically.
> - Recover correctly after application restarts.
>
> Clearly distinguish customer-visible dead-lettered webhook deliveries from RabbitMQ's internal poison-message/DLX handling.
>
> Use an injectable clock and controlled jitter source for deterministic tests.
>
> Test every response classification, retry exhaustion, clock boundaries, restart recovery, concurrent workers and malformed `Retry-After` values.
>
> Do not implement customer-facing replay APIs in this step.
>
> Run all verification commands.

**Acceptance:** Temporary failures retry automatically and terminal failures remain inspectable instead of disappearing.

---

# Step 7 — Replay, Cancellation and Endpoint Lifecycle

### Goal

Make failed deliveries recoverable without compromising historical integrity.

### Coding-agent prompt

> Implement Phase 3 Step 7: manual delivery replay and lifecycle integration.
>
> Read current endpoint soft-delete, enable/disable and signing-secret rotation semantics.
>
> Implement:
>
> 1. Manual replay of eligible dead-lettered deliveries.
> 2. Tenant-scoped replay authorization.
> 3. Idempotency protection against repeated/concurrent replay requests.
> 4. Replay audit records.
> 5. Preservation of all previous delivery attempts.
> 6. A fresh documented retry budget for replay, without erasing lifetime attempt history.
> 7. Cancellation of pending/retry-scheduled deliveries when an endpoint is deleted.
> 8. Explicit handling of disabled endpoints.
> 9. Explicit handling of already in-flight deliveries during deletion/disable.
> 10. Enforcement of signing-secret availability and current security policy on replay.
>
> Design the replay state transitions to prevent duplicate concurrent scheduling.
>
> Document that a replay may deliver an event already processed by the receiver and that receivers must implement event/delivery idempotency.
>
> Add tests for duplicate replay, cross-tenant replay, deleted endpoints, disabled endpoints, successful deliveries, replay after secret rotation and concurrent replay requests.
>
> Run all verification commands.

**Acceptance:** Failed deliveries can be safely replayed, historical attempts remain intact, and deleted endpoints cannot silently resume receiving webhooks.

---

# Step 8 — Delivery History, Attempts and DLQ APIs

### Goal

Expose the delivery engine through a usable API before creating the dashboard.

### Suggested API surface

Adapt routes to the existing project's actual `/v1` conventions.

```http
GET  /v1/deliveries
GET  /v1/deliveries/{id}
GET  /v1/deliveries/{id}/attempts
POST /v1/deliveries/{id}/replay

GET  /v1/dead-letters
GET  /v1/dead-letters/{id}
```

Avoid duplicating replay endpoints if one already exists.

### Coding-agent prompt

> Implement Phase 3 Step 8: delivery history, attempt history, dead-letter listing and replay APIs.
>
> Read existing controller patterns, authentication filters, ProblemDetail conventions, pagination and OpenAPI definitions.
>
> Implement tenant-scoped endpoints for:
>
> - Listing deliveries by status, event, endpoint and date range.
> - Retrieving one delivery.
> - Listing delivery attempts.
> - Viewing customer-visible dead-lettered deliveries.
> - Replaying an eligible delivery.
>
> Requirements:
>
> - All authorization derives from trusted tenant context.
> - Cross-tenant resources return 404.
> - Results are paginated with stable ordering and bounded page sizes.
> - Validation and errors follow existing API conventions.
> - No plaintext signing secrets, encryption ciphertext, sensitive headers or unbounded response bodies are exposed.
> - Replay writes have appropriate audit and abuse controls.
> - Response contracts match the current OpenAPI conventions.
>
> Add MockMvc and Testcontainers integration tests for positive and negative scenarios.
>
> Update `docs/openapi.yaml` and relevant developer documentation.
>
> Run all verification commands.

**Acceptance:** Customers can investigate deliveries, see why they failed and trigger authorized replay through the backend API.

---

# Step 9 — Backpressure, Fairness and Operational Observability

### Goal

Prevent failing endpoints or large customers from overwhelming the delivery infrastructure.

### Coding-agent prompt

> Implement Phase 3 Step 9: operational controls, backpressure and observability.
>
> Review the complete delivery pipeline and identify unbounded work, connection, memory and database contention risks.
>
> Requirements:
>
> - Configurable maximum global concurrent deliveries.
> - Configurable per-endpoint concurrency limits.
> - Bounded executor queues and connection pools.
> - Safe behaviour under database or broker outages.
> - Fair scheduling so one noisy endpoint/project cannot indefinitely starve others.
> - Limits on response size, attempt metadata and retained sensitive information.
> - Metrics for pending deliveries, in-progress deliveries, success/failure rates, latency, retry volume, dead-letter volume and lease recovery.
> - Structured logs with correlation IDs.
> - No secrets or full sensitive webhook payloads in logs.
> - Readiness/liveness design that does not accidentally expose administrative endpoints.
> - Graceful worker shutdown and cancellation behaviour.
> - Documented configuration defaults and operational tuning.
>
> Prefer existing Micrometer and Spring infrastructure. Do not introduce a new monitoring stack unless required.
>
> Use bounded-cardinality metric tags; avoid unrestricted event IDs, URLs and tenant IDs as metric labels.
>
> Add focused tests for concurrency limits, starvation risks, saturation and shutdown.
>
> Run all verification commands.

**Acceptance:** Delivery remains bounded, observable and recoverable under overload and dependency failures.

---

# Step 10 — End-to-End Integration Tests, Postman and Documentation

### Goal

Verify the whole event-to-webhook flow, not just individual components.

### Required end-to-end scenario

```text
1. Create endpoint
2. Create subscription
3. Publish event through ingestion API
4. Outbox publisher publishes to RabbitMQ
5. Consumer creates delivery
6. Worker claims delivery
7. HMAC signature is generated
8. HTTPS request is sent
9. Receiver verifies signature
10. Delivery and attempt records reflect the outcome
```

### Coding-agent prompt

> Implement Phase 3 Step 10: comprehensive end-to-end verification and documentation.
>
> Read the entire Phase 3 implementation and all project agent instructions.
>
> Create integration tests covering the real event-to-delivery lifecycle with PostgreSQL, RabbitMQ and a controlled HTTPS test receiver.
>
> Do not globally disable production SSRF policy or TLS verification to make localhost tests pass. Use properly isolated test transport/resolver configuration and prove production wiring remains strict.
>
> Required scenarios:
>
> 1. Successful end-to-end delivery.
> 2. One event delivered to multiple endpoints.
> 3. One failing endpoint does not block another.
> 4. Invalid signature verification fails.
> 5. Duplicate event ingestion is idempotent.
> 6. RabbitMQ redelivery does not duplicate fan-out.
> 7. Network timeout schedules retry.
> 8. HTTP 429 respects bounded retry policy.
> 9. HTTP 5xx eventually succeeds after retries.
> 10. Permanent HTTP 4xx failure becomes dead-lettered.
> 11. Retry exhaustion becomes dead-lettered.
> 12. Manual replay succeeds.
> 13. Endpoint deletion cancels queued work.
> 14. Cross-tenant delivery access is rejected.
> 15. Worker crash and lease recovery.
> 16. Application restart preserves retry schedules.
> 17. Concurrent workers preserve database state integrity.
> 18. DNS rebinding and private-address SSRF attempts cannot reach prohibited destinations.
> 19. TLS hostname mismatch is rejected.
> 20. Secret rotation and replay behave according to the documented policy.
>
> Update both the comprehensive Postman API collection and the separate deployment smoke collection for all newly implemented HTTP APIs.
>
> Ensure smoke tests are repeatable and do not accumulate uncontrolled data.
>
> Run them twice against a running local development environment.
>
> Update OpenAPI, README, architecture documentation and a receiver signature-verification example.
>
> Run:
>
> - `./gradlew spotlessApply`
> - `./gradlew clean check`
> - `./gradlew bootJar`
>
> Run configured SBOM and vulnerability checks when their prerequisites are available. Report unavailable scans as NOT VERIFIED.
>
> Produce an exact execution report with passing, failing and skipped tests. Never invent results.

**Acceptance:** A real accepted event successfully travels through the outbox, RabbitMQ, consumer, database delivery worker and signed HTTPS request, with reliable retries, DLQ and replay.

---

# Phase 3 Final Verification

After completing Steps 0–10, run a separate independent review rather than relying only on the implementation agent's reports.

### Final audit prompt

> Act as an independent principal backend engineer, security auditor and reliability reviewer.
>
> Perform a comprehensive source-code and runtime audit of the completed HookRelay Phase 3 implementation.
>
> Read `AGENTS.md`, `HOOKRELAY_OVERVIEW.md`, `docs/architecture.md`, `docs/PHASE_3_DELIVERY_ENGINE.md` and `docs/openapi.yaml`.
>
> Independently verify:
>
> 1. Event-to-delivery correctness.
> 2. Transactional outbox integration.
> 3. RabbitMQ consumer acknowledgements and duplicate handling.
> 4. Fan-out atomicity and subscription semantics.
> 5. Delivery database constraints and state transitions.
> 6. PostgreSQL worker claims, leases, fencing and crash recovery.
> 7. Actual socket-level SSRF prevention and TLS hostname verification.
> 8. HMAC signing protocol and secret rotation.
> 9. Retry policy, backoff and persistent customer-visible DLQ.
> 10. Manual replay, cancellation and endpoint lifecycle races.
> 11. Tenant isolation across every delivery API and worker query.
> 12. Backpressure, rate limits and resource exhaustion.
> 13. Sensitive-data exposure, logging and metrics.
> 14. Database migrations, including upgrades from the Phase 2 schema.
> 15. Dependency, container and build reproducibility.
> 16. End-to-end integration tests and repeatable Postman smoke tests.
>
> Inspect actual code paths rather than accepting existing tests at face value.
>
> Add regression tests for defects. Fix genuine Phase 3 defects without adding unrelated features.
>
> Run the full build, relevant security checks and real runtime integration tests.
>
> Classify every finding as Critical, High, Medium or Low.
>
> Do not claim a security control is verified unless its actual enforcement has been tested.
>
> Create `docs/PHASE_3_FINAL_VERIFICATION.md`, including:
>
> - PASS / CONDITIONAL PASS / FAIL verdict.
> - Full verification matrix.
> - Code changes and regression tests.
> - Runtime and Postman test results.
> - Security findings and residual risks.
> - Explicit at-least-once delivery limitations.
> - Production-readiness blockers.
> - Whether Phase 4 can begin.
>
> Do not manufacture passing test results or conceal environmental limitations.

---

# Definition of Done — Phase 3

Phase 3 is complete when:

- [ ] Events fan out reliably to subscribed endpoints.
- [ ] Every delivery and attempt has durable database state.
- [ ] Duplicate mesages cannot create duplicate initial delivery records.
- [ ] HTTPS request use validated, pinned destinations and proper TLS hostname verification.
- [ ] Each webhook is signed using the documented protocol.
- [ ] Multiple workers can process deliveries safely.
- [ ] Failed deliveries retry according to a persisted policy.
- [ ] Permanent/exhausted failures enter the customer-visible DLQ.
- [ ] Authorized customers can inspect and replay failed deliveries.
- [ ] Soft-deleted and disabled endpoint behaviour is enforced.
- [ ] Tenant isolation holds throughout the asynchronous pipeline.
- [ ] The delivery engine remains bounded under overload.
- [ ] Runtime integration tests and repeatable Postman smoke tests pass.
- [ ] Critical and High findings are resolved.
- [ ] OpenAPI and architecture documentation reflect the implementation.
- [ ] The final verification report is complete.

**Completion does not mean production deployment is approved.** Infrastructure egress controls, secrets management, vulnerability scanning, operational readiness and deployment hardening must also be verified before exposing the service publicly.
