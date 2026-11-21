# AGENTS.md — HookRelay Engineering Rules

> **Audience:** AI coding agents, automation agents, and human contributors.  
> **Scope:** Entire HookRelay repository. More-specific `AGENTS.md` files may add constraints for a subdirectory, but must not weaken the security requirements in this file without an explicit maintainer decision.  
> **Last reviewed:** 2026-11-13.

This file is the engineering contract for changes to HookRelay. Follow it before editing code, build files, infrastructure, migrations, or documentation. The goal is secure, maintainable, reproducible software—not merely code that compiles.

No instruction file can guarantee that software is vulnerability-free. Agents must reduce risk through supported versions, secure design, automated checks, tests, and honest reporting. Never claim a dependency or application is "secure", "safe", or "vulnerability-free" solely because a build passed.

---

## 1. Non-negotiable working rules

1. **Read before changing.** Inspect this file, the relevant source files, `README.md`, `docs/architecture.md`, `backend/build.gradle.kts`, `backend/src/main/resources/application.yml`, and related migrations/tests before making a change.
2. **Verify APIs against the actual installed framework version.** Do not answer from memory when an API, package, starter, annotation, configuration key, plugin, or compatibility claim may have changed. Prefer official Spring, Gradle, Java, PostgreSQL, RabbitMQ, OWASP, and vendor documentation.
3. **Do not invent successful verification.** Report the exact commands executed and their outcomes. Distinguish static inspection from compilation, unit tests, integration tests, vulnerability scans, and live runtime verification.
4. **Preserve existing behavior unless the task explicitly changes it.** Add regression tests before refactoring authentication, tenant scoping, idempotency, outbox processing, event persistence, or API contracts.
5. **Prefer the smallest complete change.** Do not introduce a new framework, service, library, abstraction, or microservice when the existing stack can solve the problem cleanly.
6. **No security shortcuts.** Do not weaken authentication, authorization, TLS, input validation, tenant predicates, dependency verification, vulnerability gates, or tests just to make a build pass.
7. **No silent compatibility shims.** Do not keep obsolete APIs or add legacy dependencies merely to avoid migrating code. If a temporary compatibility module is unavoidable, document its reason, owner, scope, and removal condition.
8. **Do not commit generated secrets, credentials, local databases, build outputs, or real customer payloads.** Use placeholders and `.env.example` only.
9. **Do not make network calls from a database transaction.** Keep database transactions short; never hold locks while making HTTP requests or waiting for RabbitMQ confirms.
10. **Do not claim exactly-once delivery.** The platform provides at-least-once behavior. Design every message consumer and delivery action to tolerate duplicates.

If instructions conflict, prioritize: (1) user request, (2) this file's security and correctness rules, (3) existing documented architecture, (4) local code conventions. Explain any unavoidable conflict in the final report.

---

## 2. Current technology baseline

The project currently targets the following baseline. Treat it as the **approved current baseline**, not permission to use floating versions or to upgrade everything automatically.

| Area | Current baseline | Rule |
|---|---|---|
| Java | 25 LTS (Temurin 25.0.5+8) | Compile and test with Java 25. Do not downgrade the toolchain silently. |
| Spring Boot | 4.1.2 | Latest stable on 2026-11-13; Boot 4 APIs and BOM-managed libraries only. |
| Gradle | 9.9.1 | Latest stable on 2026-11-13; Boot 4.1.2 supports Gradle 9.x. Gradle 8.13 is unsupported by Boot 4.1.2. |
| Spring Framework | 7.0.10 | Managed by the Spring Boot 4.1.2 BOM; never override independently without a documented security reason. |
| Spring AMQP | 4.1.2 | Managed by the Spring Boot 4.1.2 BOM. |
| Jackson | 3.1.7 | Managed by the Spring Boot 4.1.2 BOM; Jackson 3 APIs use `tools.jackson.*`. |
| Flyway | 12.4.0 | Managed by the Spring Boot 4.1.2 BOM, including the PostgreSQL support module. |
| Micrometer | 1.17.2 | Managed by the Spring Boot 4.1.2 BOM. |
| PostgreSQL JDBC | 42.7.14 | Managed by the Spring Boot 4.1.2 BOM; do not add a separate version. |
| Spotless | 8.11.0 | Latest Gradle formatting plugin release on 2026-11-13. |
| google-java-format | 2.0.0 | Latest formatter release compatible with Java 25 (1.37.0 threw InvocationTargetException on the Java 25 toolchain). |
| OWASP Dependency-Check | 13.0.0 | Latest Gradle SCA plugin release on 2026-11-13. |
| CycloneDX Gradle plugin | 3.5.1 | Latest plugin release on 2026-11-13. |
| PostgreSQL | 18.7 | Latest 18.x security/bugfix patch on 2026-11-13. |
| RabbitMQ | 4.4.0 | Latest stable RabbitMQ release on 2026-11-13. |
| Node.js (planned) | 26.13.0 LTS | Pin when the frontend is scaffolded. |
| Next.js (planned) | 16.4.6 | Current stable frontend line on 2026-11-13; canaries/previews are not production baselines. |
| Build DSL | Kotlin DSL | Keep build logic in `.gradle.kts`; do not introduce Groovy build files. |
| JSON | Jackson 3 | Use `tools.jackson.*` APIs, with the annotation-package exception below. |
| HTTP API | Spring MVC | Keep the application imperative/blocking unless a deliberate architecture decision approves reactive processing. |
| Persistence | PostgreSQL + JDBC | Use parameterized SQL and explicit tenant predicates. |
| Messaging | RabbitMQ + Spring AMQP | Assume duplicate delivery and broker outages. |
| Schema management | Flyway | Migrations are append-only and reviewed as production data changes. |
| Frontend | Next.js + TypeScript (planned) | Apply the same dependency, security, and reproducibility rules when added. |

### 2.1 Version selection and upgrades

- Use the newest **stable, supported, security-patched release that is compatible with the entire stack**, not automatically the numerically newest release.
- Before changing Java, Spring Boot, Gradle, plugins, database drivers, RabbitMQ clients, or infrastructure images, verify compatibility using the official compatibility matrix and release notes.
- Prefer LTS Java releases for production. Do not move to a non-LTS Java release without an explicit maintainer decision and a support/upgrade plan.
- Upgrade Spring Boot through its supported release line. Let its dependency-management BOM choose Spring Framework, Jackson, Micrometer, Hibernate (if added), and other managed versions.
- Do not independently override a Spring Boot-managed transitive dependency to chase a newer version. First check whether a newer Boot patch/BOM manages the fix. Use a documented constraint only when necessary and tested.
- Never use dynamic dependency versions such as `1.+`, `latest.release`, version ranges, snapshots, milestones, release candidates, or unpinned plugin versions in committed production builds.
- Keep plugin versions pinned. Verify plugin compatibility with the selected Gradle and Java versions before upgrading.
- Review release notes and known CVEs before an upgrade. Run all tests, dependency scans, and image scans after it.
- If an upstream project is archived, unmaintained, or has no credible security-response process, do not add it without explicit approval and a documented exception.
- Add a dependency only when it solves a real requirement that cannot be met reasonably with the JDK or existing approved stack.

### 2.2 Required evidence for an upgrade

When changing a foundational version, include in the change description:

1. Previous and new versions.
2. Official release-note and compatibility links.
3. Why the new version is appropriate (security fix, supported upgrade, required feature, or maintenance).
4. Relevant migration changes and any removed/deprecated APIs.
5. Test, dependency-scan, and container-scan results—or clearly state which checks could not run.

---

## 3. Import and API policy — prevent obsolete package usage

The application uses Spring Boot 4 and Jackson 3. Do not copy imports from older Spring Boot 2/3 or Jackson 2 examples without checking the current API.

### 3.1 Jakarta EE namespace

For Jakarta EE APIs, use `jakarta.*`, not the retired Java EE namespace:

- Servlet: `jakarta.servlet.*`
- Validation: `jakarta.validation.*`
- Persistence (only if deliberately introduced): `jakarta.persistence.*`
- Transactions (Jakarta API only when required): `jakarta.transaction.*`
- Annotation APIs (where relevant): `jakarta.annotation.*`

Do **not** apply a blind ban to every `javax.*` import. Java SE packages such as `javax.crypto.*`, `javax.net.*`, `javax.sql.*`, and `javax.naming.*` remain legitimate. Assess the exact package before changing it.

### 3.2 Jackson 3 namespace

Use the Jackson 3 package names for core and databinding APIs:

```java
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
```

Important exception: Jackson 3 retains the annotation package under `com.fasterxml.jackson.annotation.*`. For example, the existing `com.fasterxml.jackson.annotation.JsonIgnore` import is valid and must not be mechanically rewritten.

Rules:

- Do not introduce Jackson 2 core/databind imports such as `com.fasterxml.jackson.core.*` or `com.fasterxml.jackson.databind.*` in application code.
- Do not add `jackson-databind` 2.x or `jackson-core` 2.x just to make an old sample compile.
- Do not configure two JSON mappers or mix Jackson 2 and Jackson 3 models in one flow unless a documented third-party interoperability requirement makes it unavoidable.
- Use the mapper and serializers configured by Spring Boot. Avoid constructing a new mapper in application code unless there is a specific, tested need.
- When adding a Jackson annotation, confirm it is part of the Jackson 3-compatible annotation module and still uses the expected annotation package.

### 3.3 Spring Boot 4 conventions

- Use Boot 4's technology-specific starters. For MVC, use `spring-boot-starter-webmvc`; do not add the old umbrella `spring-boot-starter-web` to a Boot 4 project.
- Use only the starter needed for the feature. Do not add WebFlux to an MVC application merely to use `WebClient`; choose the HTTP client based on the worker's blocking/concurrency model and justify the choice.
- Prefer constructor injection. Do not use field injection or static application-context lookups.
- Use `RestClient` for straightforward synchronous outbound HTTP calls in blocking workers, unless a tested reason supports a different client. Do not introduce new `RestTemplate` usage.
- Use Spring's supported configuration-properties binding for groups of settings rather than scattering `@Value` strings throughout complex modules.
- Use `ProblemDetail` / consistent error contracts for API errors. Never expose exception class names, stack traces, SQL, secrets, or internal hostnames in client-facing errors.
- Check the current Boot 4 migration guide before copying examples involving auto-configuration, test annotations, Actuator, Jackson, HTTP clients, or starters.

### 3.4 General Java import hygiene

- Do not use internal JDK packages (`sun.*`, `com.sun.*`, `jdk.internal.*`) or reflective access to JDK internals.
- Do not import classes from a library's internal/implementation packages when a public API exists.
- Remove unused imports and avoid wildcard imports in production source.
- Use import statements for classes from other packages; do not use fully-qualified class names in method signatures, field declarations, variable declarations, catch clauses, `instanceof` checks, or annotations. Import the class and use its simple name. Use fully-qualified names only when necessary to resolve ambiguity between same-named classes from different packages.
- Prefer standard Java APIs over third-party utilities for simple operations.
- Prefer `record` for immutable data carriers; add Lombok only where `record` cannot be used (e.g., serialization framework constraints). Lombok is not managed by the Spring Boot BOM, so pin its version explicitly via the version catalog. For data carriers with Lombok, prefer `@Builder` with `@Value`/`@Data` over plain constructors; avoid telescoping constructors. Keep generated behavior explicit and debuggable.
- Do not suppress compiler warnings globally. Fix the cause or narrowly suppress a documented unavoidable warning.

### 3.5 Import review before completion

Search changed source files for legacy or suspicious imports. At minimum, inspect for:

- `javax.servlet`, `javax.validation`, `javax.persistence`, `javax.annotation`, `javax.ws.rs`
- `com.fasterxml.jackson.databind` and `com.fasterxml.jackson.core` (but not `com.fasterxml.jackson.annotation`)
- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.web.client.RestTemplate`
- JDK internal packages and deprecated APIs

A match is a review trigger, not always an automatic failure. Explain legitimate exceptions. Do not globally replace strings without understanding the package/API.

---

## 4. Dependency management and supply-chain security

Every direct and transitive dependency, Gradle plugin, build image, and runtime image is part of the product's attack surface.

### 4.1 Dependency admission checklist

Before adding or replacing a dependency, verify:

1. **Identity:** exact group, artifact, package name, repository, and official project URL.
2. **Maintainer health:** active maintenance, recent compatible releases, credible maintainers, security policy, and response history.
3. **Compatibility:** explicitly supports Java 25, Spring Boot 4.1.x, and Gradle 9.9.1 where applicable.
4. **Vulnerabilities:** check current advisories in OSV, GitHub Security Advisories, NVD/CVE, vendor advisories, and the project's own security page.
5. **License:** license is compatible with HookRelay's intended commercial SaaS distribution. Flag unclear, source-available, copyleft, or dual-license terms for maintainer review; do not decide legal compatibility on your own.
6. **Transitive footprint:** inspect the full dependency graph and avoid overlapping libraries or unnecessary transitive dependencies.
7. **Alternatives:** prefer JDK or already-approved Spring/Gradle features when practical.
8. **Provenance:** use official repositories and verify checksums/signatures when available.

Do not assume that a high download count, a top search result, or presence in Maven Central means a package is safe.

### 4.2 Version control and BOMs

- Use the Spring Boot dependency-management BOM for dependencies it manages.
- Do not put explicit versions on Boot-managed libraries unless there is an approved, documented override.
- Pin versions for Gradle plugins and dependencies that Boot does not manage.
- Centralize Gradle plugin versions and formatter versions in `backend/gradle/libs.versions.toml`; do not scatter duplicate version literals.
- Do not introduce multiple versions of Spring Framework, Jackson, Netty, Reactor, Micrometer, or logging implementations without a documented compatibility reason.
- Inspect dependency resolution with `./gradlew dependencies` and `./gradlew dependencyInsight --dependency <name> --configuration runtimeClasspath` when investigating a version or CVE.

### 4.3 Dependency locking

The backend should use Gradle dependency locking for reproducible resolution.

- Enable locking for all resolvable configurations (`lockAllConfigurations()` in the appropriate Gradle configuration).
- Commit generated lock state for production-relevant configurations.
- Update lock files only as part of a reviewed dependency change.
- Do not use `--write-locks` as a blind cleanup step; inspect every changed resolved version.
- A version catalog is a place to declare versions, not a substitute for locking resolved transitive dependencies.

### 4.4 Dependency verification

Use Gradle dependency verification metadata in `backend/gradle/verification-metadata.xml` when the Wrapper/build setup is ready.

- Prefer SHA-256 or stronger checksums; use PGP signature verification when the publisher provides usable signatures and the signing identity can be validated.
- Bootstrap verification metadata only from trusted repositories and a known-good dependency set.
- **Never blindly trust generated verification metadata.** Review new checksums/signatures against official publisher information and inspect the diff before committing.
- Do not accept unexpected verification metadata changes just to make CI green.
- When dependencies/plugins change, update and review verification metadata as a separate explicit part of the change.
- Dependency verification metadata (`gradle/verification-metadata.xml`) is generated with `--write-verification-metadata sha256`. The generated metadata must be reviewed against official publisher checksums before committing — do not blindly trust generated checksums. If verification fails on a transitive artifact, review the artifact's provenance before updating metadata.

### 4.5 Vulnerability scanning and exceptions

- Run a software-composition analysis (SCA) scan against the complete resolved dependency graph, including test/build plugins where the scanner supports it.
- Use OWASP Dependency-Check or an equivalent maintained scanner; verify the scanner plugin's current release and Gradle compatibility before adding/upgrading it. Do not assume the scanner database is current if its update failed.
- Configure CI to fail on confirmed High/Critical findings (normally CVSS >= 7.0), subject to reviewed false-positive handling. Do not lower the threshold merely to pass a build.
- Review findings using the advisory's affected version range, dependency path, reachability/exploitability context, and fixed version. Prefer upgrading to a vendor-supported patched release.
- Do not add broad suppressions, wildcard CVE suppressions, or permanent ignore rules. Any exception must identify the CVE/advisory, exact component/version, reason the finding is not exploitable or cannot yet be fixed, compensating controls, responsible owner, review date, and expiry/removal condition.
- Re-check exceptions periodically and remove them as soon as the issue is fixed.
- Use an SBOM (CycloneDX or another maintained standard) for release artifacts. Include direct and transitive runtime dependencies.
- Scan built container images (for OS packages and libraries) with a maintained image scanner such as Trivy. Scan source/build dependencies separately; image scanning alone is not enough.
- Include secret scanning and static analysis in CI. Never put a scanner API key or NVD API key in source control; inject it through CI secrets.
- When a scan cannot run because the database is unavailable or rate-limited, report it as **not verified**, not as zero vulnerabilities.

### 4.6 Automated update policy

Use Renovate or Dependabot (or an equivalent reviewed updater) for Gradle plugins, dependencies, GitHub Actions, and container image updates once CI is established.

- Group routine patch/minor updates where useful, but keep foundational framework, runtime, database, and security updates individually reviewable.
- Do not auto-merge major upgrades or security exceptions without successful CI and a human-approved migration review.
- Prioritize actively exploited vulnerabilities and vendor security advisories over routine feature updates.

---

## 5. Gradle and build integrity

- Use the committed Gradle Wrapper (`./gradlew` / `gradlew.bat`) for all developer and CI builds. Do not depend on a locally installed Gradle version. A global Gradle 8.13 must never be used for this Boot 4.1.2 project.
- The approved version is Gradle 9.9.1; invoke the bootstrap script from the repository root to generate launchers with the pinned `gradle:9.9.1-jdk25` image, not with an unverified local executable. All Gradle plugin versions must be centralized in `backend/gradle/libs.versions.toml`.
- Verify the Wrapper JAR checksum against the official Gradle 9.9.1 checksum before trusting a changed Wrapper JAR.
- Set `distributionSha256Sum` in `gradle-wrapper.properties` to the official distribution checksum. Never disable Wrapper validation.
- Dependency locking is enabled. Generate and review lock state after first trusted resolution; do not claim reproducibility until the lock state and dependency-verification metadata are committed.
- Do not run the build as root in CI or containers unless technically unavoidable and documented.
- Keep Gradle plugins pinned and review their own transitive dependencies and permissions.
- Avoid executing arbitrary scripts downloaded from the internet during configuration/build. Avoid unreviewed `mavenLocal()` or custom repositories.
- Use Maven Central and the Gradle Plugin Portal only where needed. Do not add unknown repositories or `flatDir` dependencies.
- Keep configuration-cache and build-cache behavior correct; do not introduce unsafe configuration-time I/O or mutable global state in Gradle scripts.
- Do not store credentials in build files, `gradle.properties` committed to Git, or command-line arguments that may be logged. Use environment variables or CI secret stores.
- Do not use `--offline` to conceal dependency-resolution failures.
- Keep toolchain language version and compiler `options.release` aligned at Java 25 unless an approved upgrade changes both deliberately.

Expected backend verification commands (run from `backend/` after the Wrapper is generated):

```bash
./gradlew --version
./gradlew clean check
./gradlew bootJar
./gradlew dependencies
```

Run security/SBOM tasks when configured:

```bash
./gradlew dependencyCheckAnalyze
./gradlew cyclonedxBom
```

If a task is not configured, do not report it as passed. State that it is not yet wired into the build/CI.

---

## 6. Architecture and package boundaries

HookRelay starts as a **modular monolith**. Do not split it into microservices until there is a demonstrated deployment, scaling, or ownership need.

Current base package: `com.hookrelay`.

Keep feature code organized by business capability:

- `auth` — API-key parsing, authentication, identity, request tenant context.
- `organization` — organization-level ownership and membership (future).
- `project` — project configuration and API-key scope.
- `endpoint` — destination registration, subscription, URL/secret configuration (future).
- `event` — event API, validation, ingestion use case, event persistence.
- `delivery` — dispatch orchestration, delivery state, attempts, scheduling (future).
- `retry` — retry policy and scheduling (future; may be a subpackage of delivery if simpler).
- `dlq` — persistent customer-visible failed-delivery management (future).
- `outbox` — database-backed publish intent and broker publisher.
- `billing` — usage accounting and subscription rules (future).
- `config` — framework/infrastructure wiring only.
- `common` — genuinely cross-cutting, stable primitives; do not turn it into a dumping ground.

Rules:

- Keep controllers thin: HTTP mapping, request validation, authorization boundary, and response mapping only.
- Put business decisions in application/domain services, not controllers, filters, SQL callbacks, or configuration classes.
- Keep persistence and external-system details behind focused repository/gateway classes when that improves testability and separation.
- Do not create generic abstractions for a single use case without a concrete second use case.
- Avoid circular package dependencies. `common` must not depend on feature packages.
- Do not expose JDBC rows, persistence models, secrets, or internal domain entities directly as public API DTOs.
- Keep API DTOs separate from persistence/domain models.
- Prefer constructor injection and immutable values. Avoid mutable singleton state.
- Document non-obvious invariants close to the code and in `docs/architecture.md` when they affect multiple modules.

---

## 7. Security requirements

Security must be considered in design, implementation, tests, and operations—not bolted on at the end.

### 7.1 Authentication, authorization, and tenant isolation

- Derive the project and organization identity from the authenticated API key/session, never from a client-supplied `projectId` or `organizationId` field.
- Every tenant-owned database query must include an explicit authenticated tenant/project predicate. Never fetch by resource ID alone and authorize afterward if a tenant-scoped query can be used.
- Test cross-tenant read, update, delete, replay, and export attempts. A guessed UUID is not authorization.
- Store only a cryptographic hash of high-entropy API key secrets; reveal the raw key only once at creation. Support rotation and revocation when management APIs are added.
- Compare secret-derived MACs using constant-time comparison APIs where applicable.
- Enforce organization/project roles for dashboard users; API-key authentication is not a substitute for user authentication/authorization.
- Use least privilege for database accounts, RabbitMQ users, cloud roles, and CI tokens.
- Authentication failures must be returned as RFC 9457 `ProblemDetail` responses. Implement an `AuthenticationEntryPoint` interface in the `auth` package and an `ApiKeyAuthenticationEntryPoint` component that writes consistent `ProblemDetail` JSON for 401 responses. Filters should delegate to the entry point rather than writing error responses inline.

### 7.2 Input validation and API behavior

- Validate all untrusted input at the boundary: headers, event type, JSON shape, body size, IDs, URLs, pagination, filters, and configuration values.
- Set explicit maximum request, event payload, response-body, and header sizes. Do not trust `Content-Length` alone; enforce limits while streaming/reading.
- Reject malformed or unsupported content types and return stable, non-sensitive error responses.
- Rate-limit by authenticated tenant/project and where necessary by source IP. Protect expensive operations and replay endpoints separately.
- Use pagination with bounded page size. Avoid unbounded list endpoints or mass replay requests.
- Treat request payloads, endpoint URLs, headers, and remote responses as untrusted data.

### 7.3 SSRF and outbound HTTP — critical for a webhook product

Any feature that allows a customer to register a destination URL creates an SSRF boundary. Before implementing endpoint delivery:

- Permit only explicitly supported schemes (normally HTTPS; HTTP only for an explicitly allowed local/test mode).
- Reject loopback, private, link-local, multicast, reserved, unspecified, IPv4-mapped, and cloud metadata addresses, including IPv6 equivalents.
- Resolve DNS and validate all resolved addresses before connecting. Defend against DNS rebinding and time-of-check/time-of-use changes; enforce the restriction at the connection/network layer where possible.
- Disable redirects by default. If redirects are ever allowed, validate every redirect target and re-resolve/re-check it before following.
- Block metadata service hostnames/IPs and non-approved ports. Do not assume hostname validation alone prevents SSRF.
- Use strict connect, response, and total deadlines; limit response-body bytes; cap concurrent connections and per-tenant dispatch rates.
- Do not forward customer credentials, internal headers, cookies, cloud identity, or secrets to a destination.
- Do not allow outbound proxy configuration from an untrusted request.
- Add integration tests for private IPv4/IPv6, localhost aliases, DNS rebinding scenarios, redirects, metadata endpoints, invalid ports, timeouts, and oversized responses.

### 7.4 Secrets and cryptography

- Never hardcode production secrets or commit them in `.env`, YAML, Docker Compose, test fixtures, logs, or screenshots.
- Use a managed secret store and envelope encryption/KMS for production endpoint signing secrets and sensitive credentials.
- HMAC-SHA256 is the default webhook signature algorithm unless a documented protocol requires another standard. Include a timestamp in the signed canonical message to support replay-window validation by receivers.
- Use cryptographic randomness from `SecureRandom` or a platform-approved key generator. Do not invent cryptographic algorithms or home-grown encryption formats.
- Support secret rotation with overlap/dual-key verification where operationally necessary.
- Redact API keys, signing secrets, authorization headers, cookies, passwords, and sensitive payload values from logs and metrics.
- Do not return secrets after initial creation. Never include secret material in exception messages.

### 7.5 Logging, privacy, and data retention

- Prefer structured logs with correlation IDs, event IDs, delivery IDs, tenant/project identifiers where safe, outcome, and duration.
- Do not log complete webhook payloads or complete remote response bodies by default. Payloads may contain financial, personal, or authentication data.
- Define retention and deletion policies for events, attempts, outbox rows, logs, and backups before production launch.
- Ensure observability data cannot become a side channel for cross-tenant data.
- Avoid high-cardinality metric labels such as event IDs, customer IDs, URL paths with IDs, or arbitrary event types.

---

## 8. Reliability and messaging invariants

### 8.1 Event ingestion and idempotency

- Persist the event and its outbox record atomically in one PostgreSQL transaction.
- A successful `202 Accepted` means the event and publish intent are committed durably—not that the customer's endpoint has been called.
- Idempotency is scoped to the authenticated project. Enforce uniqueness in the database, not only in application memory.
- Store/compare a deterministic request fingerprint. Reuse of an idempotency key with a different logical request must return a conflict.
- Handle concurrent requests with the same idempotency key and test the database race, not just a sequential replay.
- Use stable event IDs across broker retries. Consumers must deduplicate or make state transitions idempotently.

### 8.2 Transactional outbox and RabbitMQ

- PostgreSQL is the source of truth for event acceptance and outbox state; RabbitMQ is the transport.
- Claim outbox work using safe row locking/leases so multiple instances do not publish the same item concurrently under normal operation.
- Publish outside the database transaction. Use publisher confirms and mandatory/return handling; do not mark a message published merely because `convertAndSend` returned.
- Make lease expiry/recovery safe after process crashes.
- Assume duplicate publish can occur if the broker accepted a message but the publisher crashed before persisting confirmation. Consumers must be idempotent.
- Configure durable exchanges/queues and persistent messages where required. Define retry, dead-letter, overflow, TTL, and quorum/classic queue decisions explicitly.
- Do not confuse RabbitMQ's broker-level DLQ with HookRelay's customer-visible delivery DLQ. Customer delivery failures and attempts must be persisted in PostgreSQL.
- Acknowledge broker messages only after durable processing/state transition. Ensure retry paths cannot create poison-message loops.

### 8.3 Webhook delivery and retry behavior

When implemented:

- The delivery state machine must have explicit legal transitions and terminal states; document and test it.
- Promise at-least-once attempts, never exactly-once delivery.
- Classify connection errors, timeouts, HTTP 408, 429, and 5xx as retryable by default; classify ordinary 4xx as permanent by default. Allow explicit policy overrides only if safe and documented.
- Honor valid `Retry-After` values within configured bounds.
- Use exponential backoff with random jitter and a hard maximum delay/attempt policy. Avoid synchronized retry storms.
- Bound per-endpoint and per-tenant concurrency and request rate. Apply global backpressure when remote destinations are slow.
- Do not sleep a worker thread for long retry delays. Persist `next_attempt_at` and schedule due work through a bounded scheduler/queue mechanism.
- Record every attempt durably with status, timing, error classification, and safe/truncated response metadata.
- Replays must be auditable, tenant-authorized, rate-limited, and create a new execution attempt without mutating the original event payload/history.
- Ensure replay does not accidentally bypass endpoint disabled state, current security policy, or quotas.

---

## 9. PostgreSQL and Flyway rules

- Use Flyway for all schema changes. Do not rely on Hibernate/JPA auto-DDL or manual production schema edits.
- Migration files are append-only after merge/release. Never edit an already-applied migration to "fix" it; add a forward migration.
- Use descriptive, sequential migration names such as `V2__add_endpoint_subscriptions.sql`.
- Review generated SQL before committing. Keep DDL changes and data backfills safe for the deployed application version.
- Prefer expand-and-contract migrations: add nullable/new structures, deploy compatible code, backfill safely, then enforce constraints/remove old structures in a later release.
- Avoid long table locks and unbounded data migrations. Use batches for large backfills.
- Use explicit types, foreign keys, unique constraints, and indexes that reflect access patterns and tenant predicates.
- Use `timestamptz`/UTC for instants. Do not store local wall-clock timestamps for event/delivery times.
- Use parameterized JDBC statements only. Never concatenate untrusted strings into SQL, including `ORDER BY`, filters, schema/table names, or pagination fragments.
- Validate and allowlist sort/filter expressions; SQL identifiers cannot be parameterized like values.
- Choose transaction isolation and lock strategy deliberately. Avoid holding row locks across network I/O.
- Add indexes based on query plans and measured access patterns, not speculation alone. Consider partial indexes for state queues and retention/partitioning only when justified by expected volume.
- Test migrations from an empty database and from the previous released schema. Test constraints and concurrent uniqueness behavior.
- Never run destructive Flyway clean/reset operations against a shared or production database.

---

## 10. Java coding standards

- Use Java 25 language features when they improve clarity, not merely because they are new.
- Prefer `record` for immutable API DTOs/value carriers where bean validation and serialization work correctly.
- Use `Instant` for timestamps and `Duration` for elapsed/timeout values. Persist and emit instants in UTC.
- Use `BigDecimal` for money; never use `float` or `double` for currency or exact monetary quantities.
- Use `UUID` or another explicit stable identifier; do not expose database sequence implementation details.
- Validate at boundaries and keep domain invariants in the appropriate service/domain type.
- Avoid `Optional` for fields, DTO properties, or method parameters; use it mainly as a return type for a possibly absent value.
- Avoid `null` where a clear type can express absence. Do not use `Optional.get()` without checking.
- Do not catch broad `Exception`/`Throwable` unless at a process boundary where it is logged/recovered safely. Preserve causes when wrapping exceptions.
- Do not swallow exceptions or return success after partial failure.
- Use SLF4J parameterized logging; never concatenate sensitive values into logs.
- Keep methods focused, name booleans positively, and avoid abbreviations except established domain terms.
- Avoid static mutable state and global caches unless concurrency, eviction, tenant scope, and lifecycle are explicitly designed.
- Avoid blocking I/O in request threads beyond clearly bounded database operations. Outbound webhook work belongs in workers.
- Do not use `Thread.sleep` for retry orchestration. Do not create unbounded executor pools or unbounded queues.
- Use try-with-resources for closeable resources and define resource/time limits explicitly.
- Keep compiler warnings visible. Do not suppress nullness, deprecation, unchecked, or security warnings broadly.

---

## 11. API and contract standards

- Keep API paths versioned (`/v1/...`). Avoid breaking existing request/response behavior without an intentional versioning plan.
- Use explicit request/response DTOs. Do not serialize internal entities directly.
- Validate request bodies with Jakarta Bean Validation and explicit JSON-shape/size rules.
- Return stable error semantics and consistent `ProblemDetail` responses. Do not leak internal implementation details.
- Use appropriate status codes: `202` for durably accepted asynchronous work, `400` for invalid input, `401` for missing/invalid credentials, `403` for authenticated but unauthorized access, `404` for unavailable tenant-scoped resources where existence must not be disclosed, `409` for idempotency conflicts, and `429` for rate limits.
- Document headers, body limits, idempotency behavior, retry semantics, and error responses in OpenAPI.
- Treat the OpenAPI document as a contract; update it and contract tests whenever an endpoint changes.
- Use UTC ISO-8601 timestamps. Specify units in field names or documentation (`durationMs`, `retryAfterSeconds`).
- Do not put payloads or secrets in query strings.

---

## 12. Testing requirements

A production-oriented change is incomplete without tests that prove its behavior and failure modes.

### 12.1 Test layers

- **Unit tests:** deterministic business logic, retry calculations, signature canonicalization, validation, state transitions, idempotency fingerprints.
- **Spring slice tests:** MVC/controller validation, error mapping, serialization, filters, and authentication boundaries.
- **Integration tests:** PostgreSQL and RabbitMQ using Testcontainers or an equivalent isolated ephemeral environment. Do not use a developer's shared local services as a test dependency. Testcontainers containers must be declared as `@Container` static fields and cleaned up via `@AfterEach` or Testcontainers lifecycle management. Do not make tests depend on wall-clock sleeps; use bounded polling or Awaitility conditions.
- **Contract tests:** API request/response and OpenAPI alignment.
- **Security tests:** tenant isolation, invalid/revoked keys, SSRF controls, payload limits, secret redaction, authorization failures.
- **Failure tests:** broker unavailable, publisher timeout/negative confirm, process crash/lease expiry, database transient failure, duplicate messages, remote endpoint timeout, 429/5xx retries, permanent 4xx, malformed responses, and batch replay behavior.
- **Migration tests:** empty schema and supported upgrade path.

### 12.2 Test quality

- Test observable behavior, not private implementation details.
- Do not mock the database/broker in tests intended to prove SQL locking, uniqueness, migrations, publisher confirms, routing, or message acknowledgements.
- Do not make tests depend on wall-clock sleeps. Use injected `Clock`, controllable schedulers, or bounded Awaitility conditions.
- Avoid nondeterministic external network calls. Use controlled test HTTP servers.
- Test concurrent idempotency and tenant-isolation races.
- Use realistic but synthetic data. Never use actual credentials or customer payloads.
- Ensure tests clean up containers, queues, temporary files, and threads.
- Name all test methods using **BDD `givenX_whenY_thenZ` convention** — the method name should read as "given [precondition], when [action], then [expected outcome]":
  - `givenValidEvent_whenIngested_thenCreatesEventAndOutbox`
  - givenRevokedApiKey_whenIngested_thenRejected
  - givenConcurrentRequests_whenSameIdempotencyKey_thenCreatesOneEvent

### 12.3 Before declaring a task complete

Run the narrowest relevant tests first, then the full backend checks:

```bash
cd backend
./gradlew clean check
./gradlew bootJar
```

For integration or infrastructure changes, also run the relevant Testcontainers/integration profile and a local Compose smoke test when Docker is available. Report commands and skipped checks explicitly.

---

## 13. Container, infrastructure, and deployment rules

- Run the application as a non-root user. The current Dockerfile follows this principle; preserve it.
- Use minimal, supported runtime images. For production deployment, pin images by immutable digest and have an automated process to update them for security patches.
- Do not use `latest` tags in committed deployment manifests.
- Scan container images for OS/package vulnerabilities and rebuild when base-image fixes are available.
- Use read-only filesystems, dropped Linux capabilities, seccomp/AppArmor defaults, resource limits, and health/readiness probes where the hosting platform supports them.
- Expose only required ports. PostgreSQL and RabbitMQ management interfaces must not be publicly exposed in production.
- Use private networking and TLS for service-to-service traffic in shared/production environments.
- Use separate least-privilege credentials for the application, migrations, and operations. Do not use default/example passwords outside local development.
- Keep local Compose defaults clearly local-only. Require explicit secrets in staging/production; fail closed rather than silently falling back to development credentials.
- Configure graceful shutdown and ensure in-flight outbox/delivery work can be safely retried after termination.
- Define backup, restore, point-in-time recovery, and RabbitMQ recovery procedures before production onboarding.
- Store environment-specific configuration outside source control. Do not make production behavior depend on the `local` Spring profile.

---

## 14. Observability and operations

- Instrument request, outbox, publish, delivery, retry, replay, and DLQ paths with metrics and traces.
- Use bounded metric tags. Never label metrics with arbitrary payload fields, event IDs, destination URLs, or customer-provided strings.
- Include correlation context across HTTP ingestion, database outbox, RabbitMQ message, and delivery attempt.
- Track backlog depth/age, publish-confirm failures, expired leases, delivery success/error rate, retry rate, DLQ size/age, endpoint latency, rate-limit rejections, and worker saturation.
- Create alerts based on customer impact and SLOs, not every transient exception.
- Do not log secrets/payloads as a substitute for tracing. Use safe metadata and sampled/redacted diagnostics.
- Document runbooks for broker outage, database outage, outbox backlog, stuck leases, delivery retry storms, DLQ growth, signing-secret rotation, and incident response.

---

## 15. Frontend rules when Next.js is introduced

- Use TypeScript strict mode. Avoid `any`; use `unknown` and validated schemas at trust boundaries.
- Pin Node/package-manager versions and lock dependencies (`pnpm-lock.yaml`, `package-lock.json`, or the chosen manager's lock file); do not mix package managers.
- Prefer maintained, minimal dependencies. Review npm package ownership, install scripts, transitive tree, license, and advisories before adding packages.
- Run `npm audit`/the selected package manager's audit, secret scanning, and an SBOM/SCA scan in CI. Treat unresolved critical/high findings as release blockers unless formally excepted.
- Never place server API keys, signing secrets, database credentials, or privileged tokens in browser bundles or `NEXT_PUBLIC_*` variables.
- Enforce authorization on the backend. Hiding a button or route in the UI is not an authorization control.
- Validate server actions, route handlers, query parameters, and uploaded data. Protect against XSS, CSRF where relevant, open redirects, and insecure direct object references.
- Avoid rendering raw event payloads as HTML. Escape output and provide deliberate redaction/formatting.
- Do not persist sensitive customer payloads in browser local storage by default.

---

## 16. Documentation and change management

When behavior, configuration, architecture, security posture, API contracts, migrations, operational needs, or dependency versions change:

- Update the relevant README, OpenAPI file, `docs/architecture.md`, runbook, or configuration example.
- Add an Architecture Decision Record for material decisions that are hard to reverse (new database, new broker, service split, auth scheme, delivery semantics, persistence model, or major framework migration).
- Explain configuration keys, defaults, units, sensitivity, and safe deployment values.
- Keep `.env.example` placeholder-only and ensure every required variable is documented.
- Do not add vague claims such as "production-ready", "fully secure", "zero vulnerabilities", or "exactly once".

### Required completion report

At the end of a code change, report:

1. What changed and why.
2. Important design/security decisions.
3. Files or modules affected.
4. Commands/tests actually run and their results.
5. Vulnerability/SBOM/image scan results, or explicitly say they were not run/not configured.
6. Known limitations, migration steps, and follow-up work.

If any verification failed or could not run, state that plainly. Never fabricate green checks.

---

## 17. Official references to consult

Use official documentation as the primary source. Confirm version-specific URLs and content before relying on them.

- Java releases and support: https://www.oracle.com/java/technologies/java-se-support-roadmap.html
- Java documentation: https://docs.oracle.com/en/java/javase/25/
- Spring Boot reference: https://docs.spring.io/spring-boot/reference/
- Spring Boot release notes: https://github.com/spring-projects/spring-boot/releases
- Spring Boot 4 migration guide: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide
- Spring Framework reference: https://docs.spring.io/spring-framework/reference/
- Gradle compatibility: https://docs.gradle.org/current/userguide/compatibility.html
- Gradle release notes: https://docs.gradle.org/current/release-notes.html
- Gradle dependency locking: https://docs.gradle.org/current/userguide/dependency_locking.html
- Gradle dependency verification: https://docs.gradle.org/current/userguide/dependency_verification.html
- Gradle build security: https://docs.gradle.org/current/userguide/securing_dependencies.html
- Gradle securing the build tool: https://docs.gradle.org/current/userguide/securing_build_tool.html
- OWASP Dependency-Check: https://dependency-check.github.io/DependencyCheck/
- OWASP Top 10: https://owasp.org/www-project-top-ten/
- OSV vulnerability database: https://osv.dev/
- GitHub Advisory Database: https://github.com/advisories
- NVD: https://nvd.nist.gov/vuln/search
- CycloneDX: https://cyclonedx.org/
- PostgreSQL documentation: https://www.postgresql.org/docs/
- RabbitMQ documentation: https://www.rabbitmq.com/docs
- Flyway documentation: https://documentation.red-gate.com/flyway
- Docker security: https://docs.docker.com/engine/security/
- Next.js documentation: https://nextjs.org/docs

When the official docs, an example blog, and an AI-generated snippet disagree, follow the official version-specific documentation and record the decision.
