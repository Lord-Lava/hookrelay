# Phase 3 Step 1 — implementation and verification

Implemented 2026-11-20. The original Step 1 prompt and all later prompts remain in
[the delivery-engine plan](PHASE_3_WEBHOOK_DELIVERY_ENGINE.md).

## Implemented boundary

- `delivery.domain`: explicit delivery transitions, attempt outcomes, immutable
  persisted values and validated cycle limits. DEAD_LETTERED → PENDING is a domain
  transition reserved for later audited replay; no repository exposes replay yet.
- `delivery.repository.FanoutStore`: event-locked, atomic creation of the final
  fan-out marker, stored body, one delivery per event/endpoint and cycle 0. Empty
  recipient sets are final too. Duplicate calls preserve the first committed snapshot.
- `delivery.repository.DeliveryStore`: tenant-scoped reads and bounded history;
  guarded claim, STARTED, result, cancellation, horizon expiry and expired-lease
  recovery. Fresh lease tokens fence stale mutations. Claims do not consume attempts.
  Result/attempt/cycle updates commit or roll back together.
- V8–V11: explicit project ownership, composite foreign keys, lease/state/attempt
  checks, cycle history, fan-out storage, dispatch controls and due/expiry indexes.
  Existing event/endpoint-pair and lifetime attempt-number uniqueness are preserved.
  The current cycle must exist at commit. A partial unique index prevents multiple
  STARTED attempts per delivery.

No API contract, dependency version, broker topology or outbound runtime was changed.
No RabbitMQ consumer, HTTP client, dispatcher, retry algorithm, signing protocol,
endpoint cancellation reconciler or replay API was implemented. Persistence accepts
caller-classified results and due times; it never sends HTTP or calculates backoff.

The stores contain no signing secrets or response headers. The legacy `response_body`
column must remain NULL. Error values accepted by the repository are bounded
classification codes. Fan-out bodies and destination URLs are sensitive persisted
values; their record string representations omit them. The 30-day event retention
ceiling bounds new cycle deadlines, but deletion/retention jobs are still future work.

## Contracts for the next steps

Step 2 is now implemented; see [its rollout and verification record](PHASE_3_STEP_2_VERIFICATION.md).
The following describes the original Step 1 boundary.

Step 2 must select eligible active subscriptions under the same transaction and
event row lock used for fan-out persistence. The current store accepts already-selected
internal endpoint IDs and rechecks ownership/active state. It is not a subscription
selector or a public authorization boundary. Snapshot semantics are first committed
fan-out processing time, not ingestion time. The body is insert-only through the
repository; no database immutability trigger or separate privileged database role
has been introduced.

All delivery mutations use project-control → endpoint-control → endpoint → delivery
→ attempt locks. Future dispatch admission must participate in that transaction and
lock order. Dispatch-control rows do not yet enforce rates or capacity. Later code
must discover due work in bounded batches, skip contention, enforce quotas, provide
transport deadlines and schedule recovery. No current application caller invokes
these persistence primitives automatically.

Disabling/deleting an endpoint prevents new claim/STARTED operations; existing history
remains readable. Explicit cancellation fences a late result. Automatic cancellation
on deletion and expiry polling are later reconciliation work. A delivery can be
expired while disabled without inventing a transport attempt. Database fencing
cannot undo a request already received by a remote endpoint.

## Migration rollout

V1–V7 were not edited. V8 checks both legacy delivery tables before expanding them.
Any existing delivery/attempt stops migration with an operator-review error. This is
intentional: current endpoint URLs cannot establish historical destinations or
original recipient snapshots. The test fixture proves that refusal leaves the row
and successful schema version 7 intact. Do not delete history to bypass this guard.

Supported rollout: inventory delivery/attempt tables and table sizes, verify backups,
then apply V8–V11 to an empty-history deployment. Existing events/endpoints are
preserved. V10 validates constraints without a speculative historical backfill.
A populated-history deployment needs an operator-reviewed migration plan before
activation. Unique constraints on existing events/endpoints/API keys and the active
subscription index may lock populated tables; review deployment timing and use a
separately reviewed concurrent-index strategy where warranted. No worker is enabled
by these migrations, so applying them does not initiate outbound sends.

## Initial verification without Docker

Commands below ran from the repository root with the committed Gradle Wrapper and
Java 25. No dependency locking, verification or security gates were relaxed.

| Command/check | Actual result |
|---|---|
| `./backend/gradlew -p backend --no-daemon spotlessApply compileJava` | Passed. |
| `./backend/gradlew -p backend --no-daemon spotlessApply test --tests 'com.hookrelay.delivery.domain.*'` | Passed: 3 domain tests. |
| `./backend/gradlew -p backend --no-daemon spotlessApply clean check` | Failed: 61 tests passed; 9 Testcontainers classes failed initialization because Docker was unavailable. No container integration success is claimed. |
| `./backend/gradlew -p backend --no-daemon spotlessApply spotlessCheck verifyImportHygiene bootJar cyclonedxBom` | Passed; JAR built, SBOM task up to date. |
| Temporary native PostgreSQL 18.7 integration run, command below | Passed: 104 tests, comprising 18 delivery tests and 86 existing endpoint/HTTP/migration tests. |
| Final native delivery/domain run after adding successful V7-upgrade and concurrent-completion cases | Passed: 20 delivery tests and 3 domain tests. |
| `git diff --check` | Passed. |

Docker had no usable daemon/socket. An isolated rootless daemon also could not start
because `newuidmap` was missing. The supplementary database run used a disposable,
loopback-only PostgreSQL 18.7 cluster with generated credentials and isolated test
databases. Temporary copies of the committed tests replaced only their container
bootstrap/connection settings; test bodies were retained. They were supplied through
a temporary Gradle init script, leaving committed tests and build configuration intact:

```bash
./backend/gradlew -p backend --no-daemon \
  --init-script /tmp/hookrelay-step1-pg/native.init.gradle \
  test --tests '*LocalIntegrationTest'

./backend/gradlew -p backend --no-daemon \
  --init-script /tmp/hookrelay-step1-pg/native.init.gradle \
  test --tests '*DeliveryStoreLocalIntegrationTest' --tests '*DeliveryStateTest'
```

An attempted combined formatting/native-test command was rejected by Spotless because
temporary sources were outside the project directory. Formatting was then run without
the temporary init script, and the native tests ran separately. Both passed. The
cluster, generated credentials and temporary source copies were removed afterward;
these commands describe the executed supplementary harness, not a committed new test
mode. Standard reproducible verification remains `clean check` on a Docker-capable host.

Coverage includes empty installs, V4 upgrade preservation, successful V7 upgrade,
legacy-history refusal, cross-tenant access/constraints, duplicate and empty fan-out,
concurrent fan-out/claim/STARTED/completion, rollback, stale-token fencing, UNKNOWN
recovery, abandoned claims, persisted retry times/budget exhaustion, soft deletion,
disablement, horizon expiry and mandatory cycle/history constraints.

The native run does not verify the container images or RabbitMQ integration tests.
Compose smoke tests and image scanning could not run without Docker. Dependency
vulnerability scanning was not rerun for this dependency-unchanged step; no claim is
made about current vulnerability status. Compilation also reports the existing
Jackson `JsonNode.isTextual()` deprecation in `EventIngestionService`, outside this
step's changes. The Docker-backed verification gap was closed in the follow-up below.

## Docker-backed follow-up (2026-11-20)

Docker became available for a fresh verification of commits `0f8763a` and `af0890a`.
No application, dependency, test or security-gate changes were needed. The initial
Docker limitation above is historical; all committed integration tests now ran
against their actual PostgreSQL/RabbitMQ containers.

| Command (repository root unless specified) | Result |
|---|---|
| `./gradlew --no-daemon --version` from `backend/` | Gradle 9.9.1; Temurin Java 25.0.5+8. |
| `./gradlew --no-daemon clean check bootJar cyclonedxBom` from `backend/` | Passed: **181 tests, zero failures/errors/skips**; formatting, import checks, JAR and SBOM passed. |
| `node postman/check-collections.mjs` | Passed: JSON bodies/scripts for 75 requests. This validates collections, not live Newman execution. |
| `python3 -m unittest discover -s scripts -p 'test_*.py'` | Passed: 5 provenance-verifier tests. |
| `docker build --progress=plain -t hookrelay-review:local backend` | Passed with the existing strict dependency verification. |
| `python3 scripts/verify-compose.py --image hookrelay-review:local` | Passed: health, authentication, secret creation, tenant isolation, event ingestion, endpoint deletion and management exposure. Disposable services were removed. |
| `./gradlew --no-daemon dependencyCheckAnalyze --rerun-tasks` from `backend/` | **Failed** on the CVSS gate. The report contains 38 distinct advisories across 8 reported components; 16 distinct CVEs appear in the threshold-failure summary. No analysis exceptions reported. Findings are not yet triaged. |
| `scripts/scan-containers.sh` | **Failed** on four High-severity Java image findings. Tracked-source secret scan reported zero findings; no High/Critical OS-package findings were reported. |

The image scanner reports all four findings against `com.rabbitmq:amqp-client:5.31.0`:
`CVE-2026-63337`, `CVE-2026-69219`, `CVE-2026-69220`, and `CVE-2026-75516`.
Dependency-Check also reports build/test components, including Spring dependency
management, shaded Docker-client HTTP libraries, Kotlin compatibility artifacts and
Boot loader tools. These are scanner observations, not confirmed exploitability or
approved false positives. Step 1 did not change the dependency graph. Review affected
ranges, provenance and compatible BOM-managed remediation separately; no suppressions,
version overrides or threshold changes were introduced to make checks pass.

Local generated evidence (excluded from Git):

- `backend/build/reports/tests/test/index.html`
- `backend/build/reports/dependency-check/dependency-check-report.html` and `.json`
- `backend/build/reports/trivy-image.json`
- `backend/build/reports/trivy-secrets.json`

The Docker-backed functional checks are complete. **Security gates remain failed;
this verification is not a clean release approval.** Publishing the implementation
and verification commits does not resolve those findings. CodeQL was not run locally;
it remains a separate configured CI job.

## Official API references reviewed

No new dependencies or framework version changes were needed. The persistence
implementation follows the existing JDBC/TransactionTemplate conventions, checked
against [Spring programmatic transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
and [PostgreSQL 18 constraints](https://www.postgresql.org/docs/18/ddl-constraints.html).
