# HookRelay

A production-oriented starter for a multi-tenant webhook delivery platform.

## Current stack baseline

Verified against official release and compatibility sources on **2026-11-13**. See [`docs/stack-versions.md`](docs/stack-versions.md) for the detailed matrix, source links, and checksums.

- Java: Eclipse Temurin 25.0.5+8 (LTS)
- Spring Boot: 4.1.2 (latest stable; Boot 4.2.0-RC2 is a preview and is not used)
- Gradle: 9.9.1 (latest stable on review date; Gradle 8.13 is not supported by Boot 4.1.2)
- PostgreSQL: 18.7
- RabbitMQ: 4.4.0
- Next.js: 16.4.6 planned; frontend scaffold not yet created
- Node.js: 26.13.0 LTS planned for the frontend

The backend dependencies managed by Spring Boot use its 4.1.2 BOM. It currently manages Spring Framework 7.0.10, Jackson 3.1.7, Micrometer 1.17.2, Flyway 12.4.0, PostgreSQL JDBC 42.7.14, and Spring AMQP 4.1.2. Do not hardcode independent versions for these libraries.

## Included in this starter

- Java 25 / Spring Boot 4.1.2 backend built with Gradle Kotlin DSL 9.9.1
- PostgreSQL 18.7 with Flyway migrations
- RabbitMQ 4.4.0 with durable exchange, ingestion queue, and broker-level dead-letter queue
- Project-scoped API key authentication (`X-Api-Key`)
- `POST /v1/events` with payload validation and idempotency
- Transactional outbox with PostgreSQL row locking, one-at-a-time leases, persistent JSON AMQP messages, publisher confirms, and retry scheduling
- Local-only seed organization, project, and API key; the secret is not printed to logs
- Actuator health and metrics endpoints
- Spotless Java formatting, legacy-import guard, OWASP Dependency-Check, Gradle dependency locking, and CycloneDX SBOM generation tasks

Pinned Gradle plugin versions live in `backend/gradle/libs.versions.toml`; Spring runtime libraries are governed by the Spring Boot 4.1.2 BOM.

The actual outbound HTTP delivery worker, endpoint CRUD, customer dashboard, billing, and customer-visible delivery DLQ are deliberately not implemented yet. The RabbitMQ dead-letter queue in this starter protects broker-consumer failures; it is not a substitute for the persistent delivery-failure/DLQ domain model.

## Prerequisites

- Docker Engine + Docker Compose v2
- Temurin JDK 25 (for host-side builds)
- Docker is also used once to generate a trusted Gradle Wrapper if the launcher files are absent from the archive

## Generate the Gradle Wrapper (one time)

Do not run `gradle` from your PATH. A machine may have Gradle 8.13 or another version installed; that is not the project build version. The committed `gradle-wrapper.properties` pins Gradle 9.9.1 and its distribution checksum.

Linux/macOS (from repository root):

```bash
./backend/generate-wrapper.sh
```

Windows PowerShell (from repository root):

```powershell
.\backend\generate-wrapper.ps1
```

The script uses the official `gradle:9.9.1-jdk25` image, generates the Wrapper launchers/JAR, and verifies the Wrapper JAR SHA-256. Commit these generated files (`backend/gradlew`, `backend/gradlew.bat`, and `backend/gradle/wrapper/gradle-wrapper.jar`).

The Wrapper launchers/JAR are not included in this source archive because the artifact-generation environment did not have Gradle or Docker installed. The exact Wrapper properties and a checksum-verifying bootstrap script are included. Generate the Wrapper before building the Docker image. The previous archive omitted the Wrapper launcher/JAR, so invoking `gradle` directly could pick up a machine-wide Gradle 8.13 installation. This revision pins Gradle 9.9.1 and makes the Docker build invoke the repository Wrapper from a JDK-only image; after generating and committing the Wrapper files, it cannot silently select a different system Gradle version.

## Build and test

After generating and committing the Wrapper:

```bash
cd backend
./gradlew --version        # must report Gradle 9.9.1
./gradlew spotlessApply    # format the existing source tree once before the first check
./gradlew clean check bootJar
./gradlew securityAudit    # NVD_API_KEY is read by the Dependency-Check configuration
./gradlew dependencies --write-locks
./gradlew --write-verification-metadata sha256 help
```

Inspect and review generated lock and verification metadata before committing. Do not blindly accept generated checksums. On Windows, use `gradlew.bat`.

`check` runs tests, Spotless checks, and the import-hygiene guard. `securityAudit` runs OWASP Dependency-Check (fails at CVSS 7.0 or higher) and creates a CycloneDX SBOM. A stale/unavailable vulnerability database is an inconclusive scan, not a clean report.

## Run locally

```bash
cp .env.example .env
# Change passwords and the local API key before exposing any ports beyond your machine.
docker compose up --build
```

Services:

| Service | Address |
|---|---|
| Backend | http://localhost:8080 |
| Health | http://localhost:8080/actuator/health |
| Metrics | http://localhost:8080/actuator/metrics |
| PostgreSQL | localhost:5432 |
| RabbitMQ AMQP | localhost:5672 |
| RabbitMQ management | http://localhost:15672 |

The `local` Spring profile seeds a development organization and project and registers the API key from `HOOKRELAY_DEV_API_KEY`. Its value is supplied through `.env`; it is never logged. The sample key is local-only. Do not use it in shared, staging, or production environments.

## Send your first event

```bash
curl --fail-with-body -i http://localhost:8080/v1/events \
  -H 'Content-Type: application/json' \
  -H 'X-Api-Key: hr_live_localdev_development_only_change_me' \
  -H 'Idempotency-Key: payment-pay_123-succeeded' \
  -d '{
    "eventType": "payment.succeeded",
    "data": {
      "paymentId": "pay_123",
      "amount": 4999,
      "currency": "INR"
    }
  }'
```

Expected response: `202 Accepted` with an `eventId`, `createdAt`, and `status`. Repeat the request with the same idempotency key and identical content: the same event is returned with `Idempotent-Replayed: true`. Reuse that key with a different event type or payload: the API returns `409 Conflict`.

The API key format is `hr_live_<prefix>_<secret>`. Only a SHA-256 hash is stored in the database. The local profile logs the non-secret prefix only; it never logs the key value.

## Useful commands

```bash
# Generate the official Gradle Wrapper once (from repository root)
./backend/generate-wrapper.sh

# Format, then build and test (from backend/)
cd backend
./gradlew spotlessApply
./gradlew clean check bootJar

# Inspect resolved versions
./gradlew dependencies --configuration runtimeClasspath
./gradlew dependencyInsight --dependency jackson-databind --configuration runtimeClasspath

# Run SCA + SBOM (export NVD_API_KEY first for reliable NVD updates)
./gradlew securityAudit

# Run only PostgreSQL and RabbitMQ, then run Spring Boot on the host
cd ..
docker compose up -d postgres rabbitmq
cd backend
SPRING_PROFILES_ACTIVE=local \
SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/hookrelay' \
SPRING_DATASOURCE_USERNAME=hookrelay \
SPRING_DATASOURCE_PASSWORD=local_only_change_me \
SPRING_RABBITMQ_HOST=localhost \
SPRING_RABBITMQ_USERNAME=hookrelay \
SPRING_RABBITMQ_PASSWORD=local_only_change_me \
HOOKRELAY_DEV_API_KEY='hr_live_localdev_development_only_change_me' \
./gradlew bootRun

# Tail backend logs
docker compose logs -f backend

# Stop services (preserve local data)
docker compose down

# Stop and delete local database/broker volumes (DESTRUCTIVE)
docker compose down -v
```

## API contract

### `POST /v1/events`

Headers:

- `X-Api-Key` (required): project-scoped API key
- `Idempotency-Key` (optional): 1–128 characters, `[A-Za-z0-9._:-]`
- `Content-Type: application/json`

Request:

```json
{
  "eventType": "payment.succeeded",
  "data": { "paymentId": "pay_123", "amount": 4999 }
}
```

Rules:

- `eventType` must be lowercase and match `[a-z0-9]+([._-][a-z0-9]+)*` (maximum 128 characters).
- `data` must be a JSON object (not an array or scalar).
- The serialized `data` object is limited to 256 KiB; the HTTP request body is independently capped at 264 KiB before JSON parsing.
- Idempotency is scoped to a project. Replaying a key with a different request fingerprint returns `409`.
- The endpoint persists the event and an outbox message in one database transaction.

Response (`202 Accepted`):

```json
{
  "eventId": "b5d9b8e3-8c52-4cf3-a704-4f9b6af3d3ec",
  "createdAt": "2026-11-10T15:30:00Z",
  "status": "ACCEPTED"
}
```

### Authentication failures

Missing, malformed, revoked, or unknown API keys return `401 Unauthorized`. Each request is scoped to the project associated with its key. There is no client-supplied tenant/project ID.

## Architecture notes

### Ingestion and transactional outbox

The request transaction writes `events` and `outbox_messages`. The publisher claims one row at a time in a short transaction using `FOR UPDATE SKIP LOCKED`, sets an expiring lease, commits, publishes a persistent JSON message outside the transaction, waits for RabbitMQ publisher confirmation, and marks the row published. This avoids claiming a large batch whose early leases could expire while later messages wait for confirms. A process crash after broker acceptance but before the database marks the row published can still produce a duplicate. Consumers must be idempotent using the stable `eventId`.

### Tenant isolation

The API key determines the project and organization in a request-scoped `TenantContext`. Application code must use that context; do not accept a project or organization ID from an untrusted request body. Every tenant-owned query added later must include the authenticated project/organization predicate.

### Production work still required

Before onboarding real customers, implement and review:

1. Outbound destination registration and strict SSRF defenses (private/reserved IP ranges, DNS rebinding, redirect policy, metadata endpoints, port allowlists, response-size and timeout limits).
2. Delivery creation for matching endpoint subscriptions, signed HTTP dispatch, concurrency and per-endpoint rate limits.
3. Persistent delivery attempts, customer-visible DLQ, manual/batch replay, and replay audit trail.
4. API-key creation/rotation/revocation management, organization membership, dashboard authentication, authorization, and audit logs.
5. Secret encryption with KMS/envelope encryption (the migration reserves a ciphertext column for endpoint signing secrets).
6. Payload retention/deletion policies, data export, privacy controls, backup/restore and disaster-recovery procedures.
7. Distributed rate limiting, quotas, abuse protection, observability alerts, and load/chaos testing.
8. TLS termination, private networking, managed database/broker, non-default credentials, secret manager, and container image scanning.

Do not expose the local development profile or management ports to the public internet.

## Project layout

```text
hookrelay/
├── backend/
│   ├── Dockerfile
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle/wrapper/gradle-wrapper.properties
│   ├── generate-wrapper.sh / generate-wrapper.ps1
│   └── src/
│       ├── main/java/com/hookrelay/
│       │   ├── auth/          # API-key auth + tenant context
│       │   ├── common/        # API errors and request constraints
│       │   ├── config/        # RabbitMQ + app configuration
│       │   ├── event/         # event API and ingestion use case
│       │   └── outbox/        # asynchronous broker publisher
│       ├── main/resources/
│       │   ├── db/migration/  # Flyway schema
│       │   ├── application.yml
│       │   └── application-local.yml
│       └── test/java/         # unit/contract tests
├── frontend/README.md         # Next.js app planned next
├── infrastructure/
├── docs/
│   ├── architecture.md
│   ├── stack-versions.md
│   └── openapi.yaml
├── docker-compose.yml
└── .env.example
```
