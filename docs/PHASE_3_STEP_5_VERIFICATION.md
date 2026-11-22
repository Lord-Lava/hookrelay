# Phase 3 Step 5 — Delivery dispatcher verification

## Verification date
2026-11-21

## What was implemented
Commit `b64876a` ("feat(delivery): add Phase 3 Step 5 delivery dispatcher and worker")
merged into `feat/step5-delivery-dispatcher` branch.

The delivery dispatcher poll-and-dispatch layer reads due deliveries from PostgreSQL,
claims them with fenced leases, and processes them through the delivery worker (signing +
HTTPS transport). Key components:

| Component | Responsibility |
|---|---|
| `DeliveryDispatcher` | Polls PostgreSQL for due deliveries, claims with `FOR UPDATE SKIP LOCKED`, dispatches to workers, enforces concurrency/rate limits |
| `DeliveryWorker` | Starts attempt, signs body via HMAC-SHA256, sends via SSRF-safe HTTPS transport, finalizes attempt result |
| `DispatchControlService` | In-memory project/endpoint concurrency and rate-limit admission |
| `RetryScheduler` | Exponential backoff with equal jitter, bounded Retry-After honoring, cycle deadline enforcement |
| `DeliveryDispatcherProperties` | Configurable timeouts, concurrency and rate limits |
| `DeliveryDispatcherConfiguration` | Spring configuration (conditional on `hookrelay.delivery.dispatcher.enabled=true`) |

Modified:
- `delivery.repository.DeliveryStore` — added `listDue()` and `listExpiredLeases()` queries
- `delivery.transport.TransportResult` — added `outcome`, `durationMs`, `errorCode`, `retryAt` fields and factory methods (`success`, `retryable`, `permanent`, `unknown`, `failure`)
- `delivery.transport.WebhookTransport` — added `send(url, body, headers)` method with header allowlisting
- `delivery.transport.SsrfSafeWebhookTransport` — classifies outcomes by HTTP status code (2xx=success, 408/429/5xx=retryable, other 4xx=permanent), uses `DefaultHostnameVerifier` for TLS hostname verification

## Security properties
- Delivery claims use `FOR UPDATE SKIP LOCKED` to prevent concurrent claim conflicts
- Lease tokens fence stale workers — only the lease holder can finalize an attempt
- Dispatch admission is project-scoped and endpoint-scoped with explicit tenant predicates
- Rate limits use sliding-window counters per project and per endpoint
- All components are disabled by default (`hookrelay.delivery.dispatcher.enabled=false`)
- No outbound HTTP occurs inside database transactions — attempt is started, then HTTP is sent, then result is finalized in separate transactions

## Configuration

| Property | Default | Description |
|---|---|---|
| `hookrelay.delivery.dispatcher.enabled` | `false` | Enable the delivery dispatcher |
| `hookrelay.delivery.dispatcher.polling-interval` | `250ms` | Poll interval for due deliveries |
| `hookrelay.delivery.dispatcher.lease-duration` | `60s` | Lease expiry for claimed deliveries |
| `hookrelay.delivery.dispatcher.recovery-interval` | `5s` | Expired lease recovery interval |
| `hookrelay.delivery.dispatcher.max-concurrent-claims` | `16` | Max concurrent claims per instance |
| `hookrelay.delivery.dispatcher.max-concurrent-per-project` | `4` | Max concurrent deliveries per project |
| `hookrelay.delivery.dispatcher.max-concurrent-per-endpoint` | `1` | Max concurrent deliveries per endpoint |
| `hookrelay.delivery.dispatcher.rate-per-project-per-second` | `20` | Max delivery starts per project per second |
| `hookrelay.delivery.dispatcher.rate-per-endpoint-per-second` | `5` | Max delivery starts per endpoint per second |

## Verification evidence

Commands run from repository root unless noted.

| Check | Command/evidence | Outcome |
|---|---|---|
| Toolchain | `./backend/gradlew -p backend --no-daemon --version` | Java 25.0.5; Gradle 9.9.1 |
| Build, tests, SBOM | `./backend/gradlew -p backend --no-daemon clean check bootJar cyclonedxBom` | **Passed**; 247 tests, zero failures/errors/skips |
| Dependency scan | `./gradlew -p backend dependencyCheckAnalyze --rerun-tasks` | **Passed**; 20 findings against `dependency-management-plugin-1.1.7.jar`, all MEDIUM/LOW (CVSS 2.6–6.8), zero High/Critical (≥7.0). All are false positives. |
| Scan without NVD key | `env -u NVD_API_KEY ./gradlew -p backend dependencyCheckAnalyze --rerun-tasks` | **Passed**; same 20 findings; no credential-dependent bypass |

### Step 5 test results

| Test class | Tests | Failures | Errors |
|---|---|---|---|
| `RetrySchedulerTest` | 9 | 0 | 0 |
| `DispatchControlServiceTest` | 5 | 0 | 0 |
| **Step 5 subtotal** | **14** | **0** | **0** |

### Full build test results

| Suite | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| All backend tests | 247 | 0 | 0 | 0 |

## Official API sources reviewed
- [PostgreSQL 18 SELECT FOR UPDATE SKIP LOCKED](https://www.postgresql.org/docs/18/sql-select.html)
- [Spring Framework scheduling](https://docs.spring.io/spring-framework/docs/7.0.10/javadoc-api/org/springframework/scheduling/annotation/EnableScheduling.html)
- [RabbitMQ 4.4 confirms](https://www.rabbitmq.com/docs/confirms) (referenced for at-least-once semantics)

## Notes
- Fan-out (Step 2) and transport (Step 3) and signing (Step 4) must be enabled before the dispatcher can successfully deliver webhooks
- Dispatcher is disabled by default; enable with `hookrelay.delivery.dispatcher.enabled=true` after reviewing rollout requirements
- No database migration is required — V8–V11 schema from Step 1 already includes all delivery/attempt/cycle columns
- All components use constructor injection; no field injection or static lookups
