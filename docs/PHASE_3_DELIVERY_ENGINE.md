# HookRelay Delivery Engine — Phase 3 Step 6 Documentation

This document describes the durable retry lifecycle and customer-visible dead-letter handling
implemented in Phase 3 Step 6 of HookRelay's delivery engine. It complements the step prompts in
`docs/PHASE_3_WEBHOOK_DELIVERY_ENGINE.md` and the engineering rules in `AGENTS.md`.

## 1. Overview

A delivery represents one event destined for one endpoint. After fan-out creates a delivery record
(PENDING), a delivery worker claims it, performs an HTTPS POST to the endpoint, and persists the
result. Transient failures are retried on a bounded backoff schedule persisted in PostgreSQL.
Permanent failures and exhausted retry budgets transition the delivery to `DEAD_LETTERED`, which is
HookRelay's customer-visible dead-letter queue (DLQ), stored in PostgreSQL and independent of
RabbitMQ's broker-level DLQ.

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
```

`CANCELLED` is used for endpoint deletion/lifecycle reconciliation (Step 7) and is also terminal.

Legal transitions are enforced by `DeliveryStatus.canTransitionTo()` and by database constraints
(V10–V11). Terminal states (`DELIVERED`, `DEAD_LETTERED`, `CANCELLED`) cannot be overwritten by a
stale worker; lease fencing (`lease_token`) prevents a stale completion from corrupting a newer
state.

## 3. Retry classification

The transport layer (`SsrfSafeWebhookTransport`) classifies HTTP responses; the worker classifies
transport-level exceptions. The combined policy:

| Result                             | Behaviour                                    |
|------------------------------------|----------------------------------------------|
| HTTP 2xx                           | Success (`DELIVERED`)                        |
| HTTP 408                           | Retry                                        |
| HTTP 429                           | Retry; bounded `Retry-After` honored         |
| HTTP 5xx                           | Retry                                        |
| Connection timeout / network IO    | Retry                                        |
| SSRF policy violation              | Permanent (`SSRF_REJECTED`) — not retried    |
| DNS lookup timeout                 | Retry (`DNS_TIMEOUT`)                        |
| Permanent DNS resolution failure   | Permanent (`DNS_FAILURE`)                   |
| Peer EOF during TLS handshake      | Retry (`TRANSPORT_FAILURE`)                  |
| Other TLS failure (including trust or hostname) | Permanent (`TLS_FAILURE`)        |
| Most other HTTP 4xx                | Permanent failure                            |
| HTTP 3xx                           | Do not follow redirects; permanent failure   |
| Max attempts exhausted             | Dead-letter (`DEAD_LETTERED`)                |
| Retry horizon exceeded             | Dead-letter (`DEAD_LETTERED`)                |

Temporary DNS failures reported as lookup timeouts are retryable. Unknown hosts, SSRF
rejections, and TLS trust or hostname verification failures are permanent. Only a final 2xx
response (including 202/204) marks a delivery delivered.

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

## 8. Operational configuration

Configuration lives in `DeliveryDispatcherProperties` (prefix `hookrelay.delivery.dispatcher`).
The dispatcher is disabled by default (`hookrelay.delivery.dispatcher.enabled=false`) and must be
explicitly enabled.

Key settings:
- `hookrelay.delivery.dispatcher.enabled` — enable workers (default: false)

## 9. Known limitations

- At-least-once delivery: a delivery may be attempted more than once in failure scenarios.
- Replay of dead-lettered deliveries is Step 7 work and is not implemented in this step.
- No customer-facing DLQ API yet (Step 8).
- `response_body` is not captured by default (NULL); diagnostic capture is opt-in and bounded.
- Endpoint disable pauses queued work; re-enable resumes without resetting budgets.

## 10. Files affected

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
