# HookRelay Delivery Engine — Phase 3 Steps 6–7 Documentation

This document describes the durable retry lifecycle, customer-visible dead-letter handling
(Step 6), and manual replay, endpoint lifecycle, and replay audit (Step 7) of HookRelay's delivery
engine. It complements the step prompts in `docs/PHASE_3_WEBHOOK_DELIVERY_ENGINE.md` and the
engineering rules in `AGENTS.md`.

## 1. Overview

A delivery represents one event destined for one endpoint. After fan-out creates a delivery record
(PENDING), a delivery worker claims it, performs an HTTPS POST to the endpoint, and persists the
result. Transient failures are retried on a bounded backoff schedule persisted in PostgreSQL.
Permanent failures and exhausted retry budgets transition the delivery to `DEAD_LETTERED`, which is
HookRelay's customer-visible dead-letter queue (DLQ), stored in PostgreSQL and independent of
RabbitMQ's broker-level DLQ.

Dead-lettered deliveries can be manually replayed (Step 7), creating a new delivery cycle with a
fresh retry budget while preserving all previous attempts.

## 2. Delivery state machine

```
PENDING
   |
   v
IN_PROGRESS
   |
   +-- Success -------> DELIVERED        (terminal)
   |
   +-- Retryable -----> RETRY_SCHEDULED
   |                         |
   |                         v
   |                    IN_PROGRESS      (reclaimed after backoff elapses)
   |
   +-- Permanent -----> DEAD_LETTERED    (terminal)
   |
   +-- Exhausted -----> DEAD_LETTERED    (terminal)
   |
   +-- Horizon expiry --> DEAD_LETTERED  (terminal)
   |
   +-- Replay --------> PENDING          (new cycle, Step 7)
   |
   +-- Cancellation --> CANCELLED        (terminal, Step 7)
```

`CANCELLED` is used for endpoint deletion/lifecycle reconciliation (Step 7) and is also terminal.

Legal transitions are enforced by `DeliveryStatus.canTransitionTo()` and by database constraints
(V10–V11). Terminal states (`DELIVERED`, `DEAD_LETTERED`, `CANCELLED`) cannot be overwritten by a
stale worker; lease fencing (`lease_token`) prevents a stale completion from corrupting a newer
state.

## 3. Retry classification

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:cc05c90b86ec34a26c8f8474ef5eda66a7d5f545be201ab25621a3f792a1803c -->

The transport classifies HTTP responses and transport failures. For DNS failures, a
timeout, executor rejection, or interruption in the cause chain signals a retryable failure. The
worker uses that classification to apply the following policy:

| Result                             | Behaviour                                    |
|------------------------------------|----------------------------------------------|
| HTTP 2xx                           | Success (`DELIVERED`)                        |
| HTTP 408                           | Retry                                        |
| HTTP 429                           | Retry; bounded `Retry-After` honored         |
| HTTP 5xx                           | Retry                                        |
| Connection timeout / network IO    | Retry                                        |
| SSRF policy violation              | Permanent (`SSRF_REJECTED`) — not retried    |
| DNS lookup timeout                 | Retry (`DNS_TIMEOUT`)                        |
| DNS lookup interrupted             | Retry (`DNS_INTERRUPTED`)                    |
| DNS executor capacity unavailable  | Retry (`DNS_CAPACITY_UNAVAILABLE`)            |
| DNS failure without a retry cause  | Permanent (`DNS_FAILURE`)                   |
| Peer EOF during TLS handshake      | Retry (`TRANSPORT_FAILURE`)                  |
| Other TLS failure (including trust or hostname) | Permanent (`TLS_FAILURE`)        |
| Most other HTTP 4xx                | Permanent failure                            |
| HTTP 3xx                           | Do not follow redirects; permanent failure   |
| Max attempts exhausted             | Dead-letter (`DEAD_LETTERED`)                |
| Retry horizon exceeded             | Dead-letter (`DEAD_LETTERED`)                |

DNS failures without a recognized retryable cause, SSRF rejections, and TLS trust or
hostname verification failures are permanent. Only a final 2xx
response (including 202/204) marks a delivery delivered.

<!-- /coderabbit-ai-generated:v1 -->

## 4. Exponential backoff and retry scheduling

`RetryScheduler` computes `next_attempt_at` using exponential backoff with equal jitter:

- For attempt number `n` (1-based): `cap = min(maxSeconds, baseSeconds * 2^(n-1))`
- Equal jitter: delay uniformly from `[cap/2, cap]`
- Retry-After: clamped to `maxSeconds`, combined as `max(jittered, retryAfterClamped)`
- Past Retry-After dates contribute zero delay (jittered backoff used)
- The cycle deadline caps the schedule; attempts beyond it are dead-lettered

### Current retry policy values

| Policy value               | Current value |
|----------------------------|-----------|
| Base backoff               | 10 s      |
| Maximum backoff            | 1 h (3600 s) |
| Max attempts per cycle     | 8         |
| Cycle deadline             | 24 h      |
| Jitter                     | equal jitter `[cap/2, cap]` |

The jitter source is injectable (`RetryScheduler(int, int, Duration, Random)`) for deterministic
tests; the default uses `ThreadLocalRandom.current()`. The backoff calculation is overflow-safe:
it scales iteratively and stops once the value reaches `maxSeconds`, so very large attempt numbers
cannot overflow `long`.

Retry schedules are persisted in `deliveries.next_attempt_at`. Workers never sleep while waiting
for retry times; the dispatcher polls due deliveries via `listDue`.

## 5. Persistent customer-visible DLQ

HookRelay's customer-visible DLQ is logically separate from RabbitMQ's broker-level dead-letter
exchange. It is represented by PostgreSQL rows in `DEAD_LETTERED` plus attempt/cycle history.

A delivery transitions to `DEAD_LETTERED` when:
1. A permanent failure occurs (most 4xx, SSRF rejection, unknown host, TLS verification failure,
   or invalid destination).
2. Retry attempts are exhausted (cycle attempt count reaches `max_attempts`).
3. The retry horizon (cycle deadline) is exceeded.

Terminal transitions are persisted atomically in a single database transaction via
`DeliveryStore.finishAttempt`. Dead-lettered deliveries:
- Are never automatically reclaimed (excluded from `listDue` and `claim`).
- Preserve the original event and endpoint references (foreign keys survive endpoint soft-delete).
- Retain failure category (`last_error_code`), final HTTP status, terminal timestamp, and attempt
  history.
- Retain project/tenant ownership (all queries are project-scoped).
- Are idempotent under concurrent terminalization (`finishAttempt` returns `false` on duplicate).

No sensitive response headers, plaintext secrets, or unbounded response bodies are persisted.
`response_body` is written as NULL for the MVP.

## 6. Crash and lease recovery

- Workers claim deliveries with `FOR UPDATE SKIP LOCKED` and a random `lease_token`; the lease has
  an expiry (`lease_expires_at`).
- `recoverExpired` marks leases past their expiry as having an unknown outcome and reschedules the
  delivery, fencing stale completions.
- `expireHorizon` transitions PENDING/RETRY_SCHEDULED deliveries whose cycle deadline has passed to
  `DEAD_LETTERED`.
- Retry schedules survive application restarts because `next_attempt_at` is persisted in
  PostgreSQL.
- No database transaction is held open during an external HTTP request.

## 7. Concurrency and failure recovery guarantees

- Lease fencing: a stale worker cannot overwrite a newer terminal or scheduled state.
- `finishAttempt` checks the lease token before mutating; stale completions are rejected.
- `DELIVERED`/`DEAD_LETTERED`/`CANCELLED` cannot be transitioned out of.
- Concurrent workers claiming the same delivery: only one succeeds (`SKIP LOCKED` + lease token).
- Duplicate terminalization is idempotent (`finishAttempt` returns `false` the second time).
- Endpoint disable/delete excludes the delivery from `listDue` (via endpoint predicate) but retains
  history.
- All state mutations respect project/tenant isolation (project-scoped queries and FKs).

HookRelay provides at-least-once delivery, not exactly-once. A small window exists in which a
delivery may be attempted more than once (e.g., worker crash after HTTP success but before
persisting the result). Consumers should deduplicate by delivery ID.

## 9. Manual Delivery Replay (Step 7)

Dead-lettered deliveries can be manually replayed via `POST /v1/deliveries/{deliveryId}/replay`.
Replay:

- Transitions a `DEAD_LETTERED` delivery back to `PENDING` by creating a new delivery cycle with a
  fresh bounded retry budget (same `max_attempts` and `policy_version` as the original cycle).
- Preserves all previous delivery cycles and attempt history intact.
- Is idempotent: replaying with the same `Idempotency-Key` returns the same result and does not
  create a duplicate cycle.
- Is project-scoped: cross-project replay returns 404.
- Requires endpoint eligibility: the endpoint must exist, be enabled, and not be soft-deleted.
- Requires a valid signing secret: if the secret is missing, revoked, or undecryptable, replay is
  rejected.
- Applies current HMAC signing, SSRF, and endpoint policies on the retried delivery.
- Does not initiate outbound HTTP directly; the replayed delivery is picked up by the existing
  dispatcher and processed by the delivery worker.
- Persists a replay audit record atomically with the state transition, recording the previous and
  new cycle numbers, the actor API key, and a hashed idempotency key. No plaintext secrets or
  payloads are recorded.

### Replay eligibility

| Delivery state | Replayable? |
|---|---|
| `PENDING` | No |
| `IN_PROGRESS` | No |
| `RETRY_SCHEDULED` | No |
| `DELIVERED` | No |
| `DEAD_LETTERED` | Yes |
| `CANCELLED` | No |

## 10. Endpoint Lifecycle (Step 7)

### Endpoint deletion

Deleting an endpoint (soft delete):
- Prevents future fan-out to the endpoint.
- Cancels all `PENDING` and `RETRY_SCHEDULED` deliveries for the endpoint (transitioned to
  `CANCELLED` with `last_error_code='ENDPOINT_DELETED'`).
- Does not cancel `IN_PROGRESS` deliveries; those complete normally. An already-transmitted webhook
  cannot be retracted.
- Preserves all delivery and attempt records.
- Prevents further manual replay for the endpoint (endpoint lookup fails).
- Uses the same lock ordering as delivery claiming (project control → endpoint control → endpoint
  row) to avoid deadlocks with concurrent workers.

### Endpoint disabling

Disabling an endpoint:
- Pauses new delivery attempts for the endpoint.
- Preserves `PENDING` and `RETRY_SCHEDULED` deliveries; they remain durably recorded and do not
  consume additional retry attempts while paused.
- Excludes the endpoint from new fan-out and from `listDue`.
- Does not reset retry budgets.
- Re-enabling the endpoint makes eligible work available to the dispatcher again.

Workers do not enter a busy claim/release loop for paused deliveries; `listDue` excludes disabled
endpoints.

## 11. Signing-Secret Rotation and Replay (Step 7)

Replay uses the currently active signing secret returned by `EndpointService.getSigningSecret()`.
After secret rotation:

- Replay after rotation produces signatures compatible with the documented protocol (HMAC-SHA256
  with the current secret).
- Historical ciphertext is never restored.
- Plaintext signing secrets are never exposed or logged.
- Missing, revoked, or undecryptable signing secrets prevent unsafe delivery; replay is rejected
  with `SigningUnavailableException`.
- Existing encryption-key version compatibility is preserved.

## 12. Replay Audit Trail (Step 7)

Every successful replay is recorded in the `delivery_replay_audit_events` table (V12 migration),
stored separately from the transport outbox:

- `id` — UUID primary key
- `project_id` — FK to `projects(id)`
- `delivery_id` — the replayed delivery
- `previous_cycle_number` — the cycle number before replay
- `new_cycle_number` — the new cycle number after replay
- `actor_api_key_id` — FK to `api_keys(id)`, the authenticated API key
- `idempotency_key_fingerprint` — SHA-256 of the idempotency key (hash only, not the raw key)
- `action` — always `'REPLAYED'`
- `created_at` — timestamp of the replay

Audit records and scheduling changes commit atomically in a single transaction. Audit history is
append-only. No plaintext secrets, authorization headers, or payload data are recorded.

## 13. Operational configuration

Configuration lives in `DeliveryDispatcherProperties` (prefix `hookrelay.delivery.dispatcher`).
The dispatcher is disabled by default (`hookrelay.delivery.dispatcher.enabled=false`) and must be
explicitly enabled.

Key settings:
- `hookrelay.delivery.dispatcher.enabled` — enable workers (default: false)

## 14. Known limitations

- At-least-once delivery: a delivery may be attempted more than once in failure scenarios.
- No customer-facing DLQ API yet (Step 8).
- `response_body` is not captured by default (NULL); diagnostic capture is opt-in and bounded.
- Replay does not reset lifetime attempt history; historical attempts remain attached to their
  original cycles.
- Endpoint disable pauses queued work; re-enable resumes without resetting budgets.

## 15. Files affected

Step 6:
- `backend/src/main/java/com/hookrelay/delivery/application/DeliveryWorker.java` — failure
  classification, duration measurement, Retry-After overflow safety
- `backend/src/main/java/com/hookrelay/delivery/application/RetryScheduler.java` — injectable
  jitter, overflow-safe backoff
- `backend/src/test/java/com/hookrelay/delivery/application/RetrySchedulerTest.java` — deterministic
  jitter, Retry-After edge cases, overflow
- `backend/src/test/java/com/hookrelay/delivery/application/DeliveryWorkerTest.java` — failure
  classification unit tests
- `backend/src/test/java/com/hookrelay/delivery/application/DeliveryWorkerIntegrationTest.java` —
  HTTP 500→200, 429→200, 400→dead-letter, timeout integration tests
- `backend/src/test/java/com/hookrelay/delivery/repository/DeliveryStoreIntegrationTest.java` —
  horizon expiry, DLQ state, duplicate terminalization, restart recovery, attempt history

Step 7:
- `backend/src/main/java/com/hookrelay/delivery/application/DeliveryReplayService.java` — replay
  business logic, idempotency, audit persistence
- `backend/src/main/java/com/hookrelay/delivery/application/DeliveryReplayController.java` —
  `POST /v1/deliveries/{deliveryId}/replay` HTTP endpoint
- `backend/src/main/java/com/hookrelay/delivery/application/DeliveryReplayService.java` — replay
  service (same file as above)
- `backend/src/main/java/com/hookrelay/delivery/application/DeliveryNotFoundException.java` —
  delivery not found exception
- `backend/src/main/java/com/hookrelay/delivery/application/InvalidDeliveryStateException.java` —
  invalid delivery state exception
- `backend/src/main/java/com/hookrelay/delivery/application/EndpointDisabledException.java` —
  endpoint disabled exception
- `backend/src/main/java/com/hookrelay/delivery/repository/DeliveryStore.java` — replay transition,
  bulk cancel for endpoint deletion
- `backend/src/main/java/com/hookrelay/endpoint/application/EndpointService.java` — endpoint
  deletion cancels queued deliveries
- `backend/src/main/java/com/hookrelay/auth/ProjectIdentity.java` — added `apiKeyId` field
- `backend/src/main/java/com/hookrelay/auth/ApiKeyAuthService.java` — populate `apiKeyId`
- `backend/src/main/resources/db/migration/V12__add_replay_audit_events.sql` — replay audit table
- `backend/src/test/java/com/hookrelay/delivery/application/DeliveryReplayServiceTest.java` —
  replay service unit tests
- `backend/src/test/java/com/hookrelay/delivery/application/DeliveryReplayIntegrationTest.java` —
  replay and endpoint lifecycle integration tests
- `backend/src/test/java/com/hookrelay/contract/OpenApiContractTest.java` — added replay controller
  to contract test
- `docs/openapi.yaml` — added `POST /v1/deliveries/{deliveryId}/replay` endpoint and
  `ReplayResponse` schema
