# Phase 3 Step 2 — RabbitMQ consumption and transactional fan-out

Implemented on `feat/event-fanout-consumer`, based on `0259fbf`, in an isolated worktree.
The original [Step 2 prompt](PHASE_3_WEBHOOK_DELIVERY_ENGINE.md#step-2--event-consumer-and-subscription-fan-out)
and all other prompts are preserved. See ADR-013 in [architecture.md](architecture.md).

## Behavior

The consumer accepts the existing unversioned publisher envelope. AMQP `messageId`
must be the outbox UUID; project/event identity must match persisted outbox ownership,
aggregate and `EVENT_INGESTED` type. Event type and creation time must match the locked
PostgreSQL event. Consumption can precede the publisher's PUBLISHED update. Broker
`data` is shape-validated but is never used as the authoritative outbound payload.

Within one READ COMMITTED transaction, `FanoutStore` selects exact-match active
subscriptions on enabled, nondeleted endpoints belonging to the event project. Its
materialized CTE and INSERT capture the recipient set and URLs in one statement
snapshot, then create cycle 0. The marker and frozen UTF-8 JSON body commit in that
transaction, including zero recipients. The body's fields are `eventId`, `eventType`,
`createdAt`, `data`. Duplicate notifications preserve the original bytes, recipients,
destinations and cycles. Selection is at fan-out processing time, not ingestion or
necessarily the exact commit instant. Subscription removal afterward does not erase
history; sending will still require later endpoint lifecycle/security checks.

The parser caps input at 1 MiB before JSON parsing and rejects duplicate keys, trailing
documents, unsupported content types and invalid/unknown envelope fields. The service
owns its transaction so its return means commit succeeded, including deferred FK
validation. Only then does the listener call `basicAck(tag,false)`.

Permanent malformed/foreign/metadata/legacy/historical messages and deterministic
invariant failures receive one reject without requeue to the existing broker DLQ.
Transient database/transaction/connection/resource failures pause consumption; a
single supervisor stops the container, requeues unacknowledged work through channel
closure and restarts after bounded exponential backoff with jitter (1–60 seconds).
PostgreSQL lock timeouts and shutdown/resource SQLSTATEs are handled even when Spring
reports an uncategorized SQLException. There is no immediate nack/requeue loop or
sleep inside a database transaction. Broker connection recovery also retains the
container's five-second recovery interval. Error messages/bodies are not logged.

The project endpoint limit is 1,000 nondeleted rows, including disabled endpoints.
Creation serializes under the project row lock and returns 409 when full. DNS validation
still occurs before the transaction. Fan-out independently guards a 1,001-row selection
and rolls back rather than creating a partial snapshot for oversized legacy projects.
No Flyway migration, dependency change, HTTP sender, delivery retry scheduler, replay
API or customer-visible DLQ was added. The existing Step 1 explicit-recipient store API
remains available; it now shares the set-based persistence path.

## Activation and historical messages

| Environment variable | Default | Meaning |
|---|---|---|
| `FANOUT_ENABLED` | `false` | Creates/starts the consumer only when true. |
| `FANOUT_ROLLOUT_REVIEWED` | `false` | Must be true when enabling; records operator acknowledgement of the rollout checks below. |
| `FANOUT_ACCEPT_EVENTS_FROM` | unset | Required UTC instant when enabling. Unprocessed older events go to the internal DLQ; no suppression marker is fabricated. |
| `FANOUT_CONCURRENCY` | `2` | Fixed consumers per process; permitted range 1–8. |
| `FANOUT_PREFETCH` | `10` | Unacknowledged messages per consumer; permitted range 1–50. |

These map to `hookrelay.fanout.*`. The consumer group is separate from future delivery
transport settings, making fan-out-only activation possible. Tests use disposable
fresh databases and an explicit test cutoff; no production secrets or rollout flags
are configured by this change.

Before enabling in a deployed environment:

1. Inventory accepted events and queued messages, including old rotation envelopes
   that V6 could not retract. Choose a reviewed cutoff and backlog disposition.
   Old events sent today would target today's subscriptions. Do not silently process
   the whole backlog or mark suppressed events as genuine zero-recipient fan-outs.
2. Inventory projects above 1,000 nondeleted endpoints and resolve capacity exceptions.
3. Inspect actual queue type, vhost, DLX bindings and effective policies. Preserve the
   existing queue names/type; do not redeclare a live queue with incompatible arguments.
4. Configure and verify finite broker storage, overflow handling, DLQ access and alarms.
   Use policies so existing queues need not be recreated. For the default vhost/names,
   the planned limits can be applied with the following commands after reviewing any
   competing policies:

```bash
rabbitmqctl set_policy hookrelay-ingestion-bounds '^hookrelay\.events\.ingested\.v1$' \
  '{"max-length-bytes":268435456,"overflow":"reject-publish"}' --apply-to queues
rabbitmqctl set_policy hookrelay-dlq-bounds '^hookrelay\.events\.ingested\.dlq$' \
  '{"max-length-bytes":67108864,"overflow":"reject-publish"}' --apply-to queues
```

No ingestion TTL is intended; remove conflicting TTL/expiry policies through the
reviewed deployment process. These commands are deployment examples, not startup
mutations. The review flag does not programmatically inspect broker policies. Classic
queue caps exclude unacknowledged messages, and classic DLX transfer is not lossless:
a full/unavailable DLQ can lose quarantined messages. PostgreSQL remains authoritative;
monitor backlog/hand-off gaps and require controlled operator redrive, never an
unbounded poison-message loop. A quorum migration remains a separate topology change.

`FanoutStore.findPublishedWithoutSnapshot(projectId,limit)` reports published events
missing a completion marker after 60 seconds. It returns at most 100 event IDs, uses
an explicit project predicate and a five-second query timeout. It performs no automatic
republish. Operators must reconcile these gaps, including historical quarantine, before
outbox/event retention. The matching SQL can be used in an authenticated operator session;
there is no public reconciliation endpoint yet.

Metrics use bounded `hookrelay.fanout.messages` outcome labels: created, duplicate,
rejected, transient_failure and acknowledgement_failure. Existing management exposure
remains private-by-default. Outbound traffic still requires Steps 3 onward.

## Verification

The shared checkout was changed and built concurrently by another process during the
first test attempt. Work moved to `/home/snehil_sinha/projects/hookrelay-step2` before
reliable verification. Subsequent commands ran there with Java 25 and the pinned Wrapper.
Initial targeted failures exposed a test receive race/cached-channel crash simulation
and PostgreSQL lock-timeout classification; all were corrected and covered by tests.

VERIFICATION_RESULTS

Tests cover exact subscription/tenant filtering, subscriptions added after ingestion,
edits after selection but before commit, immutable trusted body/destination, concurrent
consumers, duplicate/zero-recipient notifications, publisher integration, acknowledgement
observing commit on an independent connection, real broker-channel crash before ACK,
PostgreSQL lock failure and recovery, atomic rollback, poison/oversized/foreign/legacy
messages, historical cutoff, over-capacity legacy projects, concurrent endpoint creation,
and scoped bounded reconciliation. Existing ingestion, outbox, authentication, endpoint,
SSRF, migration and Step 1 persistence regressions also run in the full suite.

## Official API sources checked

No new framework or library was introduced. APIs were checked against the installed
Spring AMQP 4.1.2/Jackson 3.1.7 artifacts and current official documentation:

- [Spring AMQP manual acknowledgement, prefetch and shutdown controls](https://docs.spring.io/spring-amqp/reference/amqp/containerAttributes.html)
- [Channel-aware listeners](https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-consumer.html)
- [PostgreSQL 18 data-modifying CTEs](https://www.postgresql.org/docs/18/queries-with.html)
- [PostgreSQL SQLSTATE codes](https://www.postgresql.org/docs/18/errcodes-appendix.html)
- [RabbitMQ queue limits and overflow policies](https://www.rabbitmq.com/docs/maxlength)
