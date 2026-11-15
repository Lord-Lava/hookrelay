
# HookRelay — Product, Architecture & Engineering Overview

> **Purpose:** This document explains what HookRelay is, why it exists, how it works, and the reasoning behind its architecture. It is intended for the project owner, future contributors, and AI coding agents.
>
> Read this document alongside `AGENTS.md` before making significant architectural or product changes.
>
> **Documentation principle:** This document describes the intended product and architecture. Not every capability described here necessarily exists in the current implementation. Always inspect the codebase to distinguish implemented functionality from planned functionality.

---

# 1. What is HookRelay?

HookRelay is a hosted, multi-tenant **webhook delivery infrastructure platform**.

It allows software companies to publish business events once and have those events reliably delivered as HTTP webhooks to the external applications that have subscribed to them.

Imagine a payment platform called **PayFlow**.

PayFlow processes payments for merchants and needs to notify each merchant's application when something happens:

- A payment succeeds.
- A payment fails.
- A refund is initiated.
- A subscription is cancelled.
- An invoice is generated.

PayFlow could build and operate its own webhook infrastructure. However, that would require implementing:

- Event storage.
- Endpoint registration.
- Event subscriptions.
- Event routing.
- Asynchronous processing.
- Retry mechanisms.
- Request signing.
- Delivery tracking.
- Failure handling.
- Dead-letter queues.
- Replay functionality.
- Rate limiting.
- Monitoring and observability.

Instead, PayFlow can integrate with HookRelay.

The integration looks like this:

1. PayFlow sends an event to HookRelay through its REST API.
2. HookRelay authenticates the request and validates the event.
3. HookRelay durably stores the event.
4. HookRelay determines which registered endpoints subscribed to that event type.
5. HookRelay asynchronously sends signed HTTP requests to those endpoints.
6. If an endpoint is temporarily unavailable, HookRelay retries delivery.
7. If delivery continues to fail, HookRelay records the failure in a persistent dead-letter system.
8. PayFlow can inspect delivery attempts and replay failed deliveries.

**The central value proposition:**

> A software company should be able to publish an event without having to build and maintain its own reliable webhook delivery infrastructure.

HookRelay is not simply a RabbitMQ wrapper or an HTTP client. It is a product built around the complete lifecycle of webhook events and their deliveries.

## 1.1 How to explain HookRelay to someone

### 15-second explanation

HookRelay is a webhook delivery infrastructure SaaS. It allows companies to send business events to their customers' systems reliably, handling retries, request signing, delivery tracking, and failed-event recovery so they don't have to build that infrastructure themselves.

### One-minute technical explanation

HookRelay is a multi-tenant, event-driven webhook delivery platform. A customer integrates through our REST API and publishes business events. We durably persist those events using PostgreSQL and a transactional outbox, then asynchronously distribute delivery tasks through RabbitMQ.

Background workers perform endpoint fan-out, HMAC request signing, and HTTP dispatch. Failed deliveries are retried using exponential backoff with jitter. Exhausted deliveries are persisted in a customer-facing dead-letter system, where customers can inspect their delivery history and replay events.

The platform also provides endpoint management, tenant isolation, API-key authentication, and delivery observability.

---

# 2. What problem does HookRelay solve?

Webhooks are a common mechanism through which one application notifies another application that something has happened.

For example:

- A payment processor notifies a merchant when a payment succeeds.
- An e-commerce platform notifies a warehouse when an order is placed.
- A subscription platform notifies an application when a subscription is cancelled.
- A communication platform notifies a customer when a message is delivered.

At first glance, webhooks seem simple.

One application makes an HTTP POST request to another application's URL.

However, operating a dependable webhook system is considerably more complicated.

## 2.1 The receiver may be unavailable

Suppose PayFlow sends a payment-success notification to a merchant.

The merchant's application might be:

- Restarting.
- Undergoing deployment.
- Experiencing a database outage.
- Overloaded.
- Temporarily unreachable.

If PayFlow makes one request and gives up, the merchant might never receive the event.

A dependable delivery system must retain the event and retry transient failures.

## 2.2 The receiver may be slow

A destination might take several seconds or even minutes to respond.

If delivery workers wait indefinitely, they become occupied and throughput decreases.

HookRelay therefore needs:

- Connection timeouts.
- Response/read timeouts.
- Overall request deadlines.
- Bounded worker concurrency.
- Response-size limits.

## 2.3 The receiver may reject or throttle requests

A destination can return different HTTP responses.

Examples:

| HTTP response | Meaning | Typical handling |
|---|---|---|
| 200 | Successful processing | Mark delivery successful |
| 400 | Invalid request | Usually permanent failure |
| 401 | Unauthorized | Usually permanent until configuration is fixed |
| 404 | Endpoint not found | Usually permanent |
| 408 | Request timeout | Retry |
| 429 | Too many requests | Retry, respect `Retry-After` |
| 500 | Internal server error | Retry |
| 503 | Service unavailable | Retry |

Not every failure should be retried in the same way.

Retrying a permanent validation error forever wastes resources. Failing to retry a temporary outage loses useful events.

HookRelay needs an explicit failure-classification and retry policy.

## 2.4 The receiver may process a request even when HookRelay sees a failure

This is one of the most important distributed-systems problems.

Imagine the following:

1. HookRelay sends an HTTP request.
2. The merchant receives it.
3. The merchant successfully processes the payment event.
4. The merchant sends `200 OK`.
5. The network drops the response.
6. HookRelay times out.

HookRelay cannot know whether the merchant processed the request.

From HookRelay's perspective, the delivery failed.

It may therefore retry the request.

The merchant might receive the same logical event twice.

This is a fundamental limitation of distributed systems, not something that can be completely eliminated by adding another database or queue.

HookRelay must design around this behavior.

## 2.5 Customers need visibility and recovery

Without delivery history, a publisher may know that it emitted an event but have no idea whether a particular customer received it.

A useful platform should answer:

- Was the event accepted?
- Which endpoints were supposed to receive it?
- Was a delivery attempted?
- What response did the endpoint return?
- How many attempts occurred?
- When is the next retry scheduled?
- Has the delivery permanently failed?
- Can the customer replay it?

Observability and recovery are core product capabilities, not just internal debugging tools.

## 2.6 Building this infrastructure internally is expensive

A company can build all of this itself, but it must maintain the infrastructure and handle operational failures.

HookRelay packages these responsibilities into a service.

---

# 3. Understand the two sides of the system

There are two distinct parties involved in a webhook delivery flow.

## 3.1 Publisher

The publisher is the software company that uses HookRelay to send events.

In our example, PayFlow is the publisher.

Its backend calls HookRelay whenever an important business event occurs.

The publisher is a HookRelay customer and authenticates using credentials such as a project API key.

## 3.2 Consumer or receiver

The consumer is the application that receives the webhook.

In our example, the merchant's backend is the receiver.

The merchant registers an endpoint URL and subscribes to relevant event types.

The receiver does not normally fetch events from HookRelay.

HookRelay makes an outbound HTTP request to the receiver's registered URL.

## 3.3 Direction of communication

This distinction is important.

HookRelay's ingestion API receives events from the publisher's application.

HookRelay then sends outbound webhook requests to the publisher's subscribers.

Therefore, HookRelay is primarily an **outbound webhook delivery platform**.

It is not primarily a service that receives arbitrary third-party webhooks on behalf of a merchant and forwards them elsewhere.

---

# 4. A practical example: one event, multiple subscribers

Imagine PayFlow has two merchants.

Merchant A registers:

`https://merchant-a.example/webhooks/payments`

Merchant B registers:

`https://merchant-b.example/hooks`

Both subscribe to:

`payment.succeeded`

A payment succeeds.

PayFlow sends an event to HookRelay:

```http
POST /v1/events
Authorization: Bearer <project-api-key>
Content-Type: application/json
Idempotency-Key: payment-9821-succeeded

{
  "type": "payment.succeeded",
  "data": {
    "payment_id": "payment-9821",
    "amount": 4999,
    "currency": "INR"
  }
}
```

HookRelay accepts and persists the event.

It finds both subscribed endpoints and creates independent delivery work:

- Delivery 1: Event `evt_123` to Merchant A.
- Delivery 2: Event `evt_123` to Merchant B.

Suppose Merchant A responds with `200 OK`, but Merchant B is temporarily down.

The outcomes are independent:

- Merchant A's delivery is marked successful.
- Merchant B's delivery is scheduled for retry.

Merchant B's outage must not hold up Merchant A or unrelated customers.

This is why three concepts must remain distinct:

| Concept | Meaning |
|---|---|
| Event | The business fact published by PayFlow |
| Delivery | The logical delivery of an event to one endpoint |
| Attempt | One actual HTTP request made as part of a delivery |

One event can create multiple deliveries.

One delivery can have multiple attempts.

---

# 5. High-level architecture

The intended initial architecture is a **modular monolith with asynchronous workers**.

We do not need to introduce multiple independent microservices prematurely.

The initial technology choices include:

- Java and Spring Boot — backend API and application services.
- PostgreSQL — durable application data and delivery state.
- RabbitMQ — asynchronous work distribution.
- Next.js — customer-facing dashboard.
- Docker — local and deployed infrastructure.

Exact dependency and runtime versions must be verified against the repository's build files and current official compatibility documentation.

Do not assume that a version mentioned in an old discussion is still the latest compatible version.

## 5.1 Architecture diagram

```text
             Publisher Application
                       |
                       |
              POST /v1/events
                 API Key
                       |
                       v
                 HookRelay API
                       |
              Authentication
              Validation
              Idempotency
                       |
                       v
                PostgreSQL
                /         \
               /           \
          Events          Outbox
                            |
                            |
                    Outbox Publisher
                            |
                            v
                        RabbitMQ
                            |
                            v
                    Background Workers
                            |
                            v
                     Subscription Lookup
                            |
                            v
                  Endpoint-specific Deliveries
                            |
                            v
                  HMAC Signing + HTTP POST
                            |
                            v
                  Customer Endpoint URLs
                            |
                   +--------+--------+
                   |                 |
                 Success           Failure
                   |                 |
                   v                 v
               Succeeded       Retry Scheduling
                                     |
                                     v
                              Future Delivery
                                     |
                               Retry Exhausted
                                     |
                                     v
                              Persistent DLQ
                                     |
                                     v
                               Dashboard
                                / Replay
```

This is a conceptual architecture. Exact boundaries and message topology should follow the actual implementation.

---

# 6. Responsibilities of major components

## 6.1 API layer

The API layer exposes the HTTP interface used by publishers and eventually by the dashboard.

For event ingestion, it should:

1. Authenticate the caller.
2. Resolve the organization/project associated with the credential.
3. Validate the request.
4. Apply idempotency rules.
5. Persist the event and outbox intent transactionally.
6. Return an appropriate response.

The API should not synchronously call every subscriber endpoint before returning.

Doing so would couple the publisher's request latency and success to the availability of every receiver.

## 6.2 PostgreSQL — durable source of truth

PostgreSQL stores the authoritative application state.

Conceptual data includes:

- Organizations.
- Projects.
- API keys.
- Events.
- Endpoints.
- Subscriptions.
- Deliveries.
- Delivery attempts.
- Outbox records.
- Retry schedules.
- Usage and billing counters.

PostgreSQL should allow HookRelay to reconstruct what happened to an event and its deliveries.

RabbitMQ is not the customer-facing event-history database.

A broker message can be consumed, acknowledged, retried, or expired depending on configuration.

Customer-visible history must be maintained in the application database.

## 6.3 RabbitMQ — asynchronous work distribution

RabbitMQ separates accepting an event from performing potentially slow network delivery.

The API can persist an event and acknowledge it quickly.

Workers process delivery tasks independently and at controlled concurrency.

RabbitMQ is a work-distribution and coordination mechanism.

It is not the ultimate source of truth for event and delivery history.

A broker message should generally identify durable work in PostgreSQL rather than being the only copy of important business state.

## 6.4 Background workers

Workers perform asynchronous operations such as:

- Reading queued work.
- Resolving subscriptions.
- Creating endpoint-specific deliveries.
- Constructing outbound requests.
- Signing requests.
- Calling destination endpoints.
- Recording attempts and outcomes.
- Scheduling retries.
- Marking exhausted deliveries.

Workers must tolerate duplicate messages and process restarts.

A message may be delivered more than once. A worker may also crash at an awkward point in the workflow.

## 6.5 Dashboard

The dashboard provides a customer-facing interface for managing and inspecting integrations.

Planned capabilities include:

- Organization and project management.
- Endpoint management.
- Event subscriptions.
- API-key management.
- Event explorer.
- Delivery history.
- Attempt details.
- Dead-letter queue.
- Replay actions.
- Usage visibility.

The dashboard is not the delivery engine. It is an interface to backend state and operations.

---

# 7. Event ingestion

A publisher sends an event to HookRelay's API.

A simplified lifecycle is:

1. Authenticate the API key.
2. Resolve the tenant/project context.
3. Validate the event type and payload.
4. Check idempotency.
5. Persist the event and outbox record in one database transaction.
6. Acknowledge acceptance to the publisher.

## 7.1 Why idempotency matters

Suppose PayFlow sends an event request.

HookRelay successfully stores it, but the HTTP response is lost.

PayFlow does not know whether the event was accepted, so it retries.

Without idempotency, HookRelay might create two separate events for the same logical operation.

An idempotency key allows PayFlow to identify retries of the same logical request.

The implementation must define:

- Idempotency-key scope.
- Retention period.
- Behavior when the same key is reused.
- Behavior when the same key is used with a different payload.

The same key with a different request body should not silently be treated as an identical request.

Idempotency at ingestion prevents duplicate event creation for recognized retries.

It does not guarantee exactly-once processing by downstream receivers.

---

# 8. Transactional outbox

The transactional outbox is one of the most important reliability mechanisms in HookRelay.

It solves the problem of coordinating PostgreSQL transactions with RabbitMQ message publication.

## 8.1 The dual-write problem

Consider a naive implementation:

1. Save an event in PostgreSQL.
2. Publish a message to RabbitMQ.

What if the database commit succeeds but the application crashes before publishing the RabbitMQ message?

The event exists in PostgreSQL, but workers never receive a message.

It may never be delivered.

Reversing the order is also unsafe.

If the message is published first and the database transaction later fails, workers may receive a message for an event that does not exist.

This is called the dual-write problem.

## 8.2 How the transactional outbox solves it

The API writes both the event and an outbox row in the same PostgreSQL transaction.

Conceptually:

```sql
BEGIN;

INSERT INTO events (...);

INSERT INTO outbox (...);

COMMIT;
```

A separate publisher process reads unpublished outbox rows and publishes corresponding messages to RabbitMQ.

After successful publication is confirmed, it marks the outbox record as published using a safe acknowledgement strategy.

The key guarantee is:

- If the transaction commits, both the event and the intent to publish exist.
- If the transaction rolls back, neither exists.

The outbox closes the database-to-broker dual-write gap.

## 8.3 Does the outbox guarantee exactly-once publication?

No.

Consider:

1. Outbox publisher publishes a message.
2. RabbitMQ accepts it.
3. Publisher crashes before marking the outbox row as published.
4. Publisher restarts.
5. The same row is published again.

This is possible and expected.

Therefore, downstream processing must tolerate duplicate messages.

## 8.4 Publisher confirms

The publisher should use RabbitMQ publisher confirms and appropriate routing/return handling.

A local send call should not automatically be interpreted as proof that the broker safely accepted the message.

Outbox processing must also support:

- Recovering after crashes.
- Safe handling of concurrent publisher instances.
- Retry of failed publications.
- Prevention of inconsistent outbox state.

---

# 9. Fan-out

Once an event is accepted, HookRelay determines which endpoints subscribed to its event type.

For example:

| Endpoint | Subscriptions |
|---|---|
| Merchant A | `payment.succeeded` |
| Merchant B | `payment.succeeded`, `payment.refunded` |
| Merchant C | `customer.updated` |

A `payment.succeeded` event should produce deliveries for Merchant A and Merchant B only.

Each endpoint gets an independent delivery lifecycle.

A failure at Merchant B must not undo Merchant A's success.

## 9.1 Subscription and endpoint configuration

An endpoint represents a destination URL and its delivery configuration.

A subscription associates an endpoint with one or more event types.

The product needs to define what happens if subscriptions change while an event is being processed.

For example:

- Should fan-out use subscriptions as they existed when the event was accepted?
- Or should it use subscriptions when fan-out actually occurs?

Either approach can be valid, but behavior must be deliberate and documented.

## 9.2 Duplicate fan-out protection

RabbitMQ messages may be duplicated.

Fan-out must therefore be idempotent.

A suitable database uniqueness constraint or transaction-safe mechanism should prevent the same logical event/endpoint pair from creating duplicate independent delivery records unintentionally.

This is separate from multiple HTTP attempts belonging to one delivery.

---

# 10. Outbound webhook delivery

For each endpoint-specific delivery, a worker constructs an HTTP request.

An illustrative request:

```http
POST /webhooks/payments
Content-Type: application/json
Webhook-Id: evt_123
Webhook-Timestamp: 1790000000
Webhook-Signature: v1,...

{
  "id": "evt_123",
  "type": "payment.succeeded",
  "data": {
    "payment_id": "payment-9821",
    "amount": 4999,
    "currency": "INR"
  }
}
```

The exact header names and payload format should be standardized in the HookRelay API contract.

The example is illustrative, not a claim that these exact headers are already implemented.

A worker should:

1. Load the event.
2. Load endpoint configuration.
3. Retrieve the endpoint's signing secret securely.
4. Construct the request body.
5. Generate a timestamp and signature.
6. Send the HTTP request with bounded timeouts.
7. Capture the response or transport error.
8. Persist the attempt.
9. Update delivery state.
10. Schedule a retry or mark the delivery complete/exhausted.

---

# 11. HMAC request signing

A receiver needs a way to verify that a webhook was created by HookRelay and has not been modified.

A common approach is HMAC-SHA256.

Conceptually:

```text
signed_content = timestamp + "." + raw_request_body

signature = HMAC_SHA256(
    endpoint_secret,
    signed_content
)
```

The signature is sent in a request header.

The receiver computes the expected signature using its secret and compares it using a constant-time comparison.

## 11.1 Important details

- Sign the exact raw bytes that are sent.
- Include a timestamp so receivers can reject stale requests.
- Include a stable event/message ID for deduplication.
- Generate a fresh timestamp and signature for each attempt if the format includes a timestamp.
- Support signing-secret rotation.
- Never expose signing secrets in logs or dashboard responses.

HMAC proves possession of the shared secret and integrity of the signed content.

It does not, by itself, prevent replay.

Timestamp freshness checks and event-ID deduplication are also necessary.

---

# 12. Retry policy

A retry is a normal part of webhook delivery.

When an attempt fails, HookRelay must decide whether to retry.

A typical starting policy:

| Result | Typical behavior |
|---|---|
| 2xx | Successful |
| Connection failure | Retry |
| Timeout | Retry |
| 408 | Retry |
| 429 | Retry; respect `Retry-After` |
| 5xx | Retry |
| Most other 4xx | Permanent failure |

The exact policy must be explicitly defined and tested.

## 12.1 Exponential backoff

Retrying immediately can overwhelm an already unhealthy endpoint.

Exponential backoff increases the delay between attempts.

A conceptual formula:

```text
delay = min(max_delay, base_delay * 2^attempt_number)
```

Random jitter is added to prevent many deliveries from retrying at exactly the same instant.

```text
scheduled_delay = backoff_with_jitter(...)
```

The actual formula, bounds, and attempt numbering should be defined in code and tests.

## 12.2 Do not sleep workers while waiting for a retry

A worker should not hold a thread for minutes just to wait until a retry is due.

Instead:

1. Record the failed attempt.
2. Calculate the next retry time.
3. Persist `next_attempt_at` in PostgreSQL.
4. Release the worker.
5. Let a scheduler or dispatcher find due deliveries later.
6. Publish work for those deliveries.

This allows workers to process other deliveries while a failed endpoint is waiting.

## 12.3 Respect Retry-After

When a destination responds with `429` or another applicable response containing a valid `Retry-After` value, HookRelay should consider that instruction.

The implementation should validate and bound external retry delays to avoid unreasonable scheduling behavior.

---

# 13. Dead-letter queue and replay

A delivery should not retry forever.

After the configured retry policy is exhausted, HookRelay marks the delivery as failed/exhausted and exposes it in a persistent customer-facing dead-letter system.

The customer should be able to:

- Inspect the event and endpoint.
- View delivery status.
- Inspect attempt history.
- Understand the final error.
- Fix the underlying issue.
- Replay the failed delivery.

## 13.1 Customer-facing DLQ vs RabbitMQ DLQ

These are different concepts.

RabbitMQ can have broker-level dead-letter exchanges and queues.

They handle messages rejected or expired at the broker level.

HookRelay's customer-facing DLQ is a product concept.

It represents webhook deliveries that could not be completed under the configured delivery policy.

It must be persisted in PostgreSQL application state, or be reconstructible from it.

It must remain available even after the corresponding RabbitMQ message has been acknowledged or removed.

## 13.2 Replay semantics

Replay should be explicit and auditable.

The implementation must define:

- Whether replay creates a new delivery or resumes an existing delivery.
- How attempt numbering works.
- How replay requests are made idempotent.
- How original failure history is retained.
- Which permissions allow replay.

Replay must not erase the original failure history.

---

# 14. Delivery guarantees

This is one of the most important architectural topics to understand.

Distributed systems cannot always determine whether a remote operation succeeded when a response is lost.

## 14.1 At-least-once delivery behavior

HookRelay should aim for at-least-once delivery behavior within its configured retention and retry policy.

It durably records work and retries eligible failures rather than silently dropping them after a single attempt.

However, this does not mean every receiver will eventually process every event.

The receiver may remain unavailable, permanently reject the payload, or exhaust the retry policy.

## 14.2 Exactly-once execution cannot be guaranteed

Consider:

1. HookRelay sends a webhook.
2. The receiver processes the event.
3. The receiver commits its database transaction.
4. The receiver sends `200 OK`.
5. The network drops the response.
6. HookRelay times out.
7. HookRelay retries.

The receiver may receive the same logical event twice.

HookRelay cannot determine with certainty whether the first request's business logic executed.

Therefore, HookRelay cannot promise exactly-once execution of arbitrary receiver-side business operations.

## 14.3 Receivers must implement idempotency

Every event should have a stable event ID.

The receiver should persist processed event IDs or otherwise make its processing idempotent.

For example:

If event `evt_123` has already been processed, a repeated request with `evt_123` should be recognized and acknowledged without charging a card or creating another order.

## 14.4 The correct product promise

A responsible description is:

> HookRelay durably records events, manages endpoint-specific delivery attempts, retries eligible failures, and exposes failed deliveries for inspection and replay. Because HTTP delivery can be ambiguous when responses are lost, receivers should deduplicate events using their stable event IDs.

Avoid claims such as:

- Guaranteed exactly-once delivery.
- Every event is guaranteed to reach every endpoint.
- No duplicate webhook requests.

---

# 15. Multi-tenancy and isolation

HookRelay is intended to serve multiple customer organizations.

One customer's events, endpoints, API keys, signing secrets, delivery logs, and usage data must not be accessible to another customer.

Tenant context should be derived from authenticated credentials or an authorized dashboard session.

Do not trust an unverified tenant ID supplied in a request body.

Isolation must be enforced in:

- Database queries.
- Repository methods.
- API authorization.
- Endpoint and subscription lookup.
- Event history.
- Delivery history.
- Replay operations.
- API-key management.
- Signing-secret management.
- Usage metering.
- Background processing.

Tests should explicitly attempt cross-tenant access and verify that it is denied.

---

# 16. Security: SSRF and outbound HTTP

One of the most important risks in HookRelay is Server-Side Request Forgery (SSRF).

A customer can register a destination URL.

HookRelay's servers later make HTTP requests to that URL.

If URL validation is weak, an attacker may try to make HookRelay access internal services.

Examples of dangerous destinations include:

- `localhost`.
- Loopback IP addresses.
- Private IPv4 ranges.
- Private or local IPv6 addresses.
- Link-local addresses.
- Cloud instance metadata endpoints.
- Internal databases.
- Internal administrative services.
- Internal control-plane endpoints.

## 16.1 Why validating a URL once is insufficient

A hostname may initially resolve to a public IP address and later resolve to a private IP address.

This is one form of DNS rebinding.

Therefore, checking the hostname only when a customer registers the endpoint is insufficient.

Protection must also apply when the outbound connection is actually made.

## 16.2 SSRF protection requirements

Before enabling customer-controlled outbound delivery, implement and test:

1. Permit only supported URL schemes, normally HTTPS and possibly HTTP according to product policy.
2. Reject loopback, private, link-local, multicast, reserved, and prohibited IPv4 and IPv6 destinations.
3. Block cloud metadata and internal-service destinations.
4. Resolve DNS and validate all returned addresses.
5. Protect against DNS rebinding.
6. Ensure the actual connection uses a validated destination address while preserving correct TLS hostname verification and HTTP Host behavior.
7. Revalidate every redirect target or disable redirects entirely.
8. Apply connection and response timeouts.
9. Limit response-body size.
10. Avoid retaining unbounded response data.
11. Restrict outbound network access at the infrastructure layer where feasible.
12. Prevent credentials or sensitive headers from being forwarded to a different host after redirects.
13. Test IPv4, IPv6, unusual address encodings, DNS changes, and redirect edge cases.

A string-based check such as rejecting URLs containing `localhost` is not an adequate SSRF defense.

## 16.3 Other security controls

The broader security baseline should include:

- Secure API-key storage and hashing practices.
- Key rotation and revocation.
- Encryption and access control for endpoint signing secrets.
- Strict tenant authorization.
- Request-size limits.
- Rate limits.
- Safe logging.
- Secret and sensitive-payload redaction.
- Secure dashboard authentication and session handling.
- Dependency vulnerability scanning.
- Container image scanning.
- Audit records for sensitive actions such as replay and key rotation.

---

# 17. Isolation, fairness, and backpressure

One noisy or unhealthy customer endpoint must not consume all available resources.

Useful controls include:

- Per-endpoint concurrency limits.
- Per-endpoint request-rate limits.
- Per-project or per-tenant quotas.
- Global worker concurrency bounds.
- Queue-depth monitoring.
- Backpressure when overloaded.
- Circuit-breaker or temporary-disable behavior for persistently unhealthy destinations, if supported by product policy.

A broken endpoint should not block unrelated endpoints.

The platform should also be careful about event ordering.

Unless ordering is explicitly designed, implemented, and tested, do not promise that events arrive in the same order in which they were published.

Parallel workers and retries can change arrival order.

---

# 18. Observability

Observability is a product feature because customers need to understand what happened to their events.

For each event and delivery, HookRelay should expose useful information such as:

- Event ID.
- Event type.
- Project context.
- Creation and acceptance time.
- Destination endpoint.
- Current delivery status.
- Attempt count.
- Attempt timestamps.
- Attempt durations.
- HTTP status code.
- Bounded and safely redacted response excerpt.
- Error category.
- Retry decision.
- Next scheduled attempt time.
- Final failure reason.

Internally, HookRelay should monitor:

- Event ingestion rate.
- Outbox backlog.
- Oldest unpublished outbox record age.
- RabbitMQ queue depth.
- Consumer throughput.
- Delivery success rate.
- Delivery failure rate.
- Retry volume.
- Delivery latency percentiles.
- Worker saturation.
- DLQ growth.
- Per-tenant traffic.

Logs, metrics, and traces should use correlation identifiers such as:

- Event ID.
- Delivery ID.
- Attempt ID.

Never log API keys, signing secrets, authorization headers, or unrestricted sensitive payloads.

---

# 19. Important domain concepts

These concepts should have consistent meanings throughout the codebase, database schema, API, logs, and documentation.

| Concept | Meaning |
|---|---|
| Organization | A customer/account boundary in HookRelay |
| Project | A logical environment or application owned by an organization |
| API key | Credential used by a publisher to authenticate to a project |
| Event | A business fact submitted by a publisher |
| Event type | A name such as `payment.succeeded` |
| Endpoint | A destination URL configured to receive webhooks |
| Subscription | Association between an endpoint and event types |
| Delivery | Logical delivery of one event to one endpoint |
| Attempt | One actual HTTP request within a delivery |
| Outbox record | Durable intent to publish asynchronous work |
| Retry schedule | Persisted time/policy for another attempt |
| DLQ | Customer-visible failed/exhausted delivery state |

Keep these distinctions clear.

In particular:

**An event is not a delivery, and a delivery is not an attempt.**

---

# 20. Usage and pricing

A future commercial model may use tiers based on event volume, delivery volume, or another usage measure.

These are not interchangeable.

Suppose one accepted event is sent to five subscribed endpoints.

- Accepted events: 1.
- Logical deliveries: 5.
- HTTP attempts: at least 5, potentially more due to retries.

If pricing is based on accepted events, this counts as one event.

If pricing is based on deliveries, it counts as five deliveries.

If retries are billable attempts, endpoint failures increase usage.

The product must define its billable unit clearly before implementing billing or advertising plan limits.

Illustrative pricing ideas discussed during planning included Free, Starter, Growth, and Scale tiers.

Any specific price or quota is a hypothesis until validated with customers and operating costs.

Do not treat example prices as committed product decisions.

---

# 21. Market context and differentiation

Webhook delivery is an established product category.

Some existing products and projects include:

- [Svix](https://www.svix.com/)
- [Hookdeck](https://hookdeck.com/)
- [Convoy](https://github.com/frain-dev/convoy)

Implementing retries and a dashboard alone does not create a defensible product. The market already understands these capabilities.

Potential differentiation areas to investigate:

- Simple developer-first API.
- Predictable pricing for smaller software companies.
- Excellent delivery debugging and replay experience.
- Strong documentation.
- SDKs and integrations.
- Straightforward onboarding.
- Integrations for Java/Spring and other backend ecosystems.
- Self-hosted deployment option later, if customers want it.

These are hypotheses to validate through customer conversations and competitor research, not established competitive advantages.

---

# 22. Product roadmap

The project should be built in stages.

## Phase 1 — Foundation

- Backend project and build reproducibility.
- API-key authentication.
- Project and tenant context.
- Event ingestion API.
- PostgreSQL persistence.
- Idempotency.
- Transactional outbox.
- RabbitMQ integration.
- Publisher confirms.
- Baseline tests.
- Local development environment.

## Phase 2 — Endpoint management and security

- Endpoint CRUD.
- Endpoint validation.
- Event-type subscriptions.
- Secret generation and storage.
- Secret rotation.
- Tenant authorization.
- SSRF protection.
- Endpoint-level configuration and limits.

SSRF protections are mandatory before enabling customer-controlled outbound delivery.

## Phase 3 — Delivery engine

- Event fan-out.
- Idempotent delivery creation.
- Worker processing.
- HMAC request signing.
- Safe outbound HTTP client.
- Timeouts.
- Response-size limits.
- Attempt persistence.
- Retry classification.
- Retry scheduling.

## Phase 4 — Reliability and recovery

- Persistent customer-facing DLQ.
- Delivery history.
- Attempt inspection.
- Replay.
- Rate limits.
- Concurrency limits.
- Backpressure.
- Failure isolation.
- Metrics, logs, and tracing.
- Retention and cleanup policies.

## Phase 5 — Customer dashboard

- Organization and project views.
- Endpoint management.
- Subscription management.
- API-key management.
- Event explorer.
- Delivery details.
- Attempt history.
- DLQ.
- Replay workflow.
- Usage visibility.

## Phase 6 — Commercial readiness

- Usage metering.
- Plan limits.
- Billing.
- Customer onboarding.
- Public documentation.
- SDKs.
- Sample applications.
- Production deployment.
- Monitoring and alerting.
- Backups.
- Incident procedures.

Do not implement every phase at once.

Make the current stage correct, secure, and testable before introducing the next major capability.

---

# 23. Engineering principles

These principles should guide implementation decisions.

1. **PostgreSQL is the source of truth.** Broker messages coordinate work; they do not replace durable application state.
2. **Assume duplicate messages.** Make ingestion, outbox publication, fan-out, and worker processing safe under repetition.
3. **Persist important state transitions.** Delivery progress must survive process restarts.
4. **Keep event, delivery, and attempt separate.** They are different domain concepts.
5. **Isolate tenants and endpoints.** One customer's failure must not compromise others.
6. **Do not block workers while waiting for retry time.** Persist the next due time and schedule future work.
7. **Treat customer-controlled URLs as hostile input.** SSRF protection is a release requirement before outbound delivery.
8. **Be honest about delivery guarantees.** Never claim exactly-once receiver-side processing.
9. **Make failures inspectable and recoverable.** Delivery history, attempt details, DLQ, and replay are core product features.
10. **Avoid premature microservices.** A modular monolith and separate worker execution are sufficient initially.
11. **Test failure paths.** Broker outages, process crashes, duplicate messages, timeouts, tenant-boundary violations, and DNS/redirect edge cases matter as much as happy paths.
12. **Document behavior.** API contracts, retry semantics, signature formats, state transitions, and guarantees must be explicit.

---

# 24. What HookRelay is not

To avoid scope confusion, HookRelay is not intended to be:

- A general-purpose replacement for Kafka or RabbitMQ.
- Merely a queue-management dashboard.
- A synchronous HTTP proxy that waits for every receiver before acknowledging an event.
- A system that guarantees exactly-once execution inside arbitrary customer applications.
- A system whose customer-facing delivery history depends on messages still existing in RabbitMQ.
- A safe outbound HTTP service merely because it validates URLs once during endpoint registration.

HookRelay may use messaging infrastructure internally, but its product abstraction is reliable webhook delivery.

---

# 25. Complete lifecycle walkthrough

Let's follow a payment event from beginning to end.

1. PayFlow's application processes a payment.
2. PayFlow decides that `payment.succeeded` should be emitted.
3. PayFlow sends the event to HookRelay with its project API key and an idempotency key.
4. HookRelay authenticates PayFlow.
5. HookRelay validates the request.
6. HookRelay checks whether the idempotency key represents an already accepted request.
7. HookRelay stores the event and an outbox record in one PostgreSQL transaction.
8. HookRelay acknowledges the accepted event to PayFlow.
9. An outbox publisher reads the pending record.
10. The publisher sends a message to RabbitMQ.
11. A background worker consumes the message.
12. The worker resolves the relevant endpoint subscriptions.
13. HookRelay creates one independent delivery for every matching endpoint.
14. A delivery worker loads the event and endpoint configuration.
15. It applies the outbound-network security policy to the destination.
16. It constructs and signs the HTTP request.
17. It sends the request with bounded timeouts and response limits.
18. If the endpoint responds successfully, HookRelay records the attempt and marks that delivery successful.
19. If the endpoint has a retryable failure, HookRelay records the attempt and persists a future retry time.
20. A scheduler later makes the delivery eligible for another attempt.
21. No worker needs to sleep while waiting.
22. If retries are exhausted, HookRelay marks the delivery failed/exhausted.
23. The customer can inspect the failure in the persistent DLQ.
24. After fixing the receiver, the customer can replay the delivery.
25. The receiver uses the stable event ID to avoid applying the same business event twice.

This is the complete product lifecycle HookRelay is being built to support.

---

# 26. Open architectural decisions

The architecture establishes the direction, but some choices must be explicitly decided as implementation progresses.

### Event ingestion

- What is the exact event envelope?
- What are the request and response contracts?
- What are the idempotency-key scope and retention period?
- What happens when an idempotency key is reused with a different payload?
- At what exact point is an event considered accepted?

### Fan-out

- Does fan-out use subscription state at ingestion time or fan-out time?
- What uniqueness constraints prevent duplicate deliveries?
- How are subscription changes handled for already accepted events?

### Delivery

- What are the exact delivery and attempt state machines?
- Which HTTP responses are retryable?
- What is the maximum attempt count?
- What is the maximum retry age?
- What is the retry schedule?
- How are `Retry-After` values validated and bounded?
- Is event ordering supported?

### Signing

- What is the exact HMAC signing format?
- Which headers are standardized?
- How are secrets generated and stored?
- How does secret rotation work?

### Network security

- Are redirects disabled or safely revalidated?
- Which URL schemes and ports are permitted?
- How are DNS rebinding and IPv6 handled?
- How are connection-time IP checks enforced?

### Data management

- What are payload and response-body limits?
- What is the event retention period?
- What is the delivery-history retention period?
- What data is redacted or deleted?
- What is the replay data model?

### Commercial model

- What exactly counts toward usage?
- Are retries billable?
- Are event and delivery quotas separate?
- How are limits enforced?

Do not let these decisions emerge accidentally from whichever implementation is easiest.

Record the selected behavior in architecture/API documentation and cover it with tests.

---

# 27. Guidance for future contributors and AI coding agents

Before changing code:

1. Read this document to understand the product and domain.
2. Read `AGENTS.md` and follow its repository-specific instructions.
3. Inspect the existing implementation instead of assuming every planned capability already exists.
4. Check the actual build files and dependency versions.
5. Verify APIs and version compatibility using current official documentation when necessary.
6. Preserve the distinction between event, delivery, and attempt.
7. Preserve PostgreSQL as the durable source of truth.
8. Design for duplicate messages, process crashes, and ambiguous network outcomes.
9. Enforce tenant scope at every relevant data-access and authorization boundary.
10. Do not implement outbound endpoint delivery without robust SSRF controls.
11. Add tests for failure paths and security boundaries, not only happy paths.
12. Update documentation when behavior, API contracts, state transitions, or guarantees change.
13. Report precisely which tests, builds, and security checks were actually run.
14. Never claim that a test, build, or scan passed if it was not executed.

AI-generated code is a proposal, not proof of correctness.

Review the design, inspect the diff, run the project's checks, and verify security-sensitive behavior independently.

---

# 28. Useful reference material

These resources provide background for the concepts used in HookRelay.

- [Svix](https://www.svix.com/) — webhook infrastructure product and documentation.
- [Hookdeck](https://hookdeck.com/) — event and webhook delivery infrastructure.
- [Convoy](https://github.com/frain-dev/convoy) — open-source webhook infrastructure.
- [OWASP Cheat Sheet Series](https://cheatsheetseries.owasp.org/) — security guidance, including webhook-related security practices.
- [RabbitMQ documentation](https://www.rabbitmq.com/docs) — exchanges, queues, acknowledgements, publisher confirms, and reliability.
- [Spring Boot documentation](https://docs.spring.io/spring-boot/index.html) — framework reference.
- [PostgreSQL documentation](https://www.postgresql.org/docs/) — transactions, constraints, locking, and database behavior.

Use official documentation for the exact versions pinned by the repository.

External product documentation is useful for learning patterns, but it is not automatically the specification for HookRelay.

---

# Final mental model

If you remember only one thing, remember this:

**HookRelay accepts a business event once, durably records it, figures out which customer endpoints should receive it, and manages each endpoint's delivery lifecycle independently — including signing, retries, visibility, failure handling, and replay.**

- PostgreSQL remembers what happened.
- RabbitMQ helps distribute the work.
- Background workers perform delivery.
- HMAC allows receivers to verify webhook authenticity.
- Retry scheduling handles temporary failures.
- The persistent DLQ makes exhausted failures visible and recoverable.
- The dashboard makes the entire lifecycle understandable to customers.
- Security controls prevent customer-configured destinations from turning HookRelay into a path into internal networks.

**That is what we are building.**
