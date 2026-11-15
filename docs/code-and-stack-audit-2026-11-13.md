# Code and stack audit — 2026-11-13

## Scope

Reviewed the generated backend source packages (`auth`, `common`, `config`, `event`, `outbox`), Flyway schema, Gradle build, Dockerfiles/Compose, profile configuration, README, and the planned frontend placeholder. This is a static source review, not a successful compilation, integration test, vulnerability scan, or runtime penetration test.

## Corrected in this revision

| Finding | Change |
|---|---|
| Local bootstrap logged the complete API key at startup. | Removed secret logging; only the non-secret prefix is logged. |
| API-key lookup performed a second SQL update while the lookup `ResultSet` callback was still active. | Materialize the key metadata first, compare the stored hash in constant time, then update `last_used_at` after the query callback returns. |
| API-key secret regex accepted arbitrary characters, including whitespace/control characters. | Restrict secret characters to `[A-Za-z0-9_-]`, 16–200 chars; retain 256-char total upper bound. |
| Outbox publisher leased an entire batch while processing it serially and waiting for confirms. | Claim one row at a time so lease duration starts immediately before publish. |
| Outbox publisher sent a Java String through a converter, leaving message content type/conversion ambiguous. | Send UTF-8 bytes explicitly as persistent `application/json` messages with stable AMQP message ID and correlated confirms. |
| Outbox failure text could persist raw exception messages. | Persist bounded exception-class error codes, not exception messages. |
| `hookrelay.outbox.enabled` was configured but not honored. | Publisher is conditional on the setting. |
| Base application configuration contained generic fallback DB/broker passwords. | Passwords are now mandatory environment variables; local dev key is isolated in `application-local.yml`. |
| Request-body wrapper accepted arbitrary charset labels for JSON and returned a new input stream on every `getInputStream()` call. | Read JSON as UTF-8 and cache a single request input stream/reader with the Servlet API mutual-exclusion behavior. |
| Docker Compose referenced PostgreSQL 18.6 despite a newer 18.7 security/bugfix release. | Updated to `postgres:18.7-alpine`. |
| Build had no enforced import guard, SCA task, SBOM task, or dependency lock policy. | Added import-hygiene Gradle task, Spotless, OWASP Dependency-Check, CycloneDX, dependency locking, and a Gradle version catalog for all plugin/formatter versions. NVD API key is read from `NVD_API_KEY` without storing a secret in source. |
| Validation contract test kept its Bean Validation factory open for the test JVM lifetime. | Close the `ValidatorFactory` after the test class completes. |
| An installed system Gradle could be invoked because Wrapper launcher/JAR were absent (which is how a local Gradle 8.13 could be picked up). | Added pinned, checksum-protected Wrapper properties and scripts that generate the wrapper inside Gradle 9.9.1/JDK25 official image and verify wrapper JAR checksum. The Docker build now uses the repository Wrapper from a JDK-only image, so a global Gradle cannot silently be selected. |

## Version baseline verified

- Java Temurin 25.0.5+8 (LTS)
- Spring Boot 4.1.2 (latest stable; 4.2.0-RC2 is preview)
- Gradle 9.9.1 (latest stable; Gradle 8.13 is outside Boot 4.1.2's supported Gradle 8 minimum of 8.14)
- PostgreSQL 18.7 (latest 18.x patch on the review date)
- RabbitMQ 4.4.0 (latest stable on review date)
- pgJDBC 42.7.14 current upstream; actual version remains Boot BOM-managed
- Spotless 8.11.0, google-java-format 1.37.0, OWASP Dependency-Check 13.0.0, CycloneDX Gradle plugin 3.5.1
- Planned frontend only: Next.js 16.4.6, Node.js 26.13.0 LTS. No frontend dependencies have been installed.

## Source-level compatibility checks

- Spring Boot 4 uses technology-specific starters such as `spring-boot-starter-webmvc`.
- Jackson core/databind APIs use `tools.jackson.*`; `com.fasterxml.jackson.annotation.*` remains the valid annotations namespace and is not a stale import.
- Jakarta Servlet and Validation imports use `jakarta.*`.
- Spring Boot BOM manages transitive Spring, Jackson, Flyway, Spring AMQP, Micrometer, and JDBC driver versions; no manual per-dependency overrides are added.
- The Gradle import guard fails on Jackson 2 core/databind imports and retired Java EE packages while intentionally allowing Java SE `javax.*` packages and Jackson annotation imports.

## Residual risks / work not yet implemented

1. The outbound webhook worker, endpoint registry, SSRF-safe URL resolution/egress policy, delivery attempts, replay, and customer-visible DLQ are not implemented. Do not expose outbound delivery until these controls exist.
2. Ingestion currently lacks distributed tenant-level rate limiting, abuse controls, API-key rotation endpoints, and public key issuance.
3. Database integration tests are not yet present. Add Testcontainers tests for Flyway, transaction rollback, idempotency races, concurrent outbox claiming, publisher confirm/return handling, and lease recovery.
4. Current tests are a small unit/contract suite; add coverage for auth query behavior and the revised outbox publisher/store.
5. Actuator exposure must be secured and restricted in any shared/production deployment. Current Compose port bindings are loopback-only for local use.
6. Production images should be pinned by verified digest, continuously scanned, signed, and updated by reviewed automation. Version tags alone are mutable.
7. Generate and commit the actual Gradle Wrapper scripts/JAR by running the provided bootstrap script in a Docker-capable environment. Generate/commit dependency lock state and reviewed Gradle dependency-verification metadata after first trusted resolution.
8. Run the SCA scan with a current NVD database and API key; scan built images with Trivy; use a secret scanner in CI. No scan can prove vulnerability absence.

## Verification performed for this archive

- Inspected all Java source files and the database migration.
- Checked that no `com.fasterxml.jackson.core.*`, `com.fasterxml.jackson.databind.*`, or retired `javax.servlet/validation/persistence/transaction/annotation` imports remain in source (Jackson `com.fasterxml.jackson.annotation` is intentionally valid).
- Validated JSON/YAML/TOML/Gradle text structure with static parsing where available and checked ZIP integrity.
- Did **not** run Gradle Kotlin DSL evaluation, Java 25 compilation, JUnit tests, Docker build/Compose startup, dependency resolution, OWASP Dependency-Check, CycloneDX generation, or Trivy. This execution environment has JDK 21, no Gradle binary, and no Docker daemon; these checks remain pending on a Java 25/Docker-enabled machine. No claim is made that the dependencies or container images are vulnerability-free.
