# HookRelay technology and version baseline

**Verified on:** 2026-11-13 (UTC date). Re-check official release pages before every foundational upgrade. This is a snapshot, not a promise that these will remain latest.

## Production and development baseline

| Layer | Selected version | Selection rationale / control |
|---|---:|---|
| Java | Eclipse Temurin 25.0.5+8 (LTS) | Current Temurin 25 patch at review date; compile with Java 25 toolchain. |
| Spring Boot | 4.1.2 | Latest stable at review date. Spring Boot 4.2.0-RC2 is a preview and is not selected for production. |
| Spring Framework | 7.0.10+ | Managed by the Spring Boot 4.1.2 BOM. Never override independently without a documented security/compatibility reason. |
| Gradle | 9.9.1 | Latest stable release at review date; includes fixes for three recently disclosed Gradle security issues. |
| Gradle Wrapper | 9.9.1 `-bin` | Distribution SHA-256 is pinned in `gradle-wrapper.properties`; wrapper JAR checksum is verified by the generation script. |
| Gradle DSL | Kotlin DSL | `build.gradle.kts` and `settings.gradle.kts`. |
| PostgreSQL | 18.7 | Latest 18.x patch at review date; Docker image `postgres:18.7-alpine`. |
| PostgreSQL JDBC | 42.7.14 | Latest pgJDBC at review date; version selected through Spring Boot's BOM. |
| RabbitMQ | 4.4.0 | Latest stable RabbitMQ release at review date; use the official management image for local development. |
| Spring AMQP | 4.1.2 line | Boot-managed; do not pin separately. |
| Flyway | 12.4.0 line | Boot-managed; PostgreSQL support module is a runtime dependency. |
| Jackson | 3.1.7 line | Boot-managed Jackson 3; core/databind imports use `tools.jackson.*`. Jackson annotations still use `com.fasterxml.jackson.annotation.*`. |
| Micrometer | 1.17.2 line | Boot-managed; Prometheus registry is explicitly included. |
| Next.js (planned) | 16.4.6 | Current stable release at review date; no frontend runtime/package has been scaffolded yet. |
| Node.js (planned) | 26.13.0 LTS | Current active LTS patch at review date; introduce `.nvmrc` and lockfile when frontend is scaffolded. |

## Quality and supply-chain tooling

| Tool | Version | Purpose |
|---|---:|---|
| Spotless Gradle plugin | 8.11.0 | Enforce Java formatting in `check`. |
| google-java-format | 2.0.0 | Deterministic Java formatter. Upgraded from 1.37.0 because it is incompatible with the Java 25 toolchain. |
| OWASP Dependency-Check Gradle plugin | 13.0.0 | SCA scan; fail on CVSS >= 7.0 in the configured audit task. |
| CycloneDX Gradle plugin | 3.5.1 | Generate a resolved dependency SBOM. |

All Spring framework and third-party runtime dependencies should use the Spring Boot 4.1.2 dependency BOM. Do not add explicit versions to BOM-managed libraries. Versions in this document describe the BOM's selected baseline and should be checked against `./gradlew dependencyInsight` after a trusted dependency resolution.

## Compatibility checks verified

- Spring Boot 4.1.2 supports Java 17 through 27 and supports Gradle 8.14+ (8.x line) or Gradle 9.x. Gradle 8.13 is below Spring Boot's supported Gradle 8 minimum.
- Gradle 9.9.1 supports running on Java 25.
- The Gradle 9.9.1 release notes recommend upgrading to 9.9.1 and list three security fixes included in that patch.
- PostgreSQL 18.7 was released on 2026-11-12; the official PostgreSQL 18.7 Alpine image tag is available.
- RabbitMQ 4.4.0 is the latest RabbitMQ stable release at review date.
- Next.js 16.4.6 is the current stable release at review date; 16.5 canary builds are previews and are not used.

## Important reproducibility notes

1. Never invoke a random system `gradle` executable for project work. Use `./gradlew` / `gradlew.bat` after the Wrapper launchers and JAR have been generated and committed.
2. The archive contains the exact Wrapper properties and a checksum-verifying generator. The launcher scripts/JAR are not included because this build environment cannot execute Gradle or Docker to generate them. From the repository root, run `./backend/generate-wrapper.sh` (Linux/macOS) or `./backend/generate-wrapper.ps1` (Windows) once, then commit the generated Wrapper files.
3. Dependency locking is enabled in the build. The first trusted online dependency resolution must generate and commit lock state. Generate Gradle artifact verification metadata with `--write-verification-metadata sha256`, review it against official publishers, then commit it. Do not treat unreviewed generated checksums as trustworthy.
4. Docker tags are version-pinned but mutable. For production releases, resolve and pin each base image by its verified digest, then use an automated image-update PR process. Scan every final image with Trivy or an equivalent scanner.
5. OWASP Dependency-Check requires a current vulnerability database; an API key (`NVD_API_KEY`) is recommended in CI. An unavailable/stale database means the audit is inconclusive, not clean.

## Official references

- [Spring Boot 4.1.2 system requirements](https://docs.spring.io/spring-boot/4.1.2/system-requirements.html)
- [Gradle 9.9.1 release notes](https://docs.gradle.org/9.9.1/release-notes.html)
- [Gradle 9.9.1 compatibility matrix](https://docs.gradle.org/9.9.1/userguide/compatibility.html)
- [Spring Boot 4.1.2 managed dependency coordinates](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)
- [Gradle distribution checksums](https://github.com/gradle/gradle-distributions/releases/tag/v9.9.1)
- [Eclipse Temurin 25 releases](https://github.com/adoptium/temurin25-binaries/releases)
- [PostgreSQL 18.7 release announcement](https://www.postgresql.org/about/news/postgresql-187-1712-1616-1520-1425-and-19-rc-2-released-3442/)
- [Official PostgreSQL Docker image tags](https://hub.docker.com/_/postgres/tags)
- [RabbitMQ release information](https://www.rabbitmq.com/release-information)
- [pgJDBC current version](https://jdbc.postgresql.org/download/)
- [Next.js releases](https://github.com/vercel/next.js/releases)
- [Node.js 26.13.0 LTS release](https://nodejs.org/en/blog/release/v26.13.0)
- [Spotless Gradle plugin](https://plugins.gradle.org/plugin/com.diffplug.spotless)
- [OWASP Dependency-Check Gradle plugin](https://plugins.gradle.org/plugin/org.owasp.dependencycheck)
- [CycloneDX Gradle plugin](https://plugins.gradle.org/plugin/org.cyclonedx.bom)
- [Spring Boot 4.1.2 dependency versions](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)
