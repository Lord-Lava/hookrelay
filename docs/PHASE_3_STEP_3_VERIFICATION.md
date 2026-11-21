# Phase 3 Step 3 — SSRF-safe outbound HTTP transport verification

## Verification date
2026-11-20

## What was implemented
Commit `df27dfb` ("feat(delivery): add Phase 3 Step 3 SSRF-safe outbound HTTP transport (#11)") merged into `main`.

The transport validates DNS at connection time and pins connections to validated addresses only. Key components:

| Component | Responsibility |
|---|---|
| `WebhookTransport` | Testable transport abstraction interface |
| `SsrfSafeWebhookTransport` | Apache HttpClient 5 implementation with SSRF-safe connection handling |
| `SsrfAddressValidator` | Validates individual resolved IP addresses against public-address policy |
| `WebhookTransportProperties` | Configurable timeouts, body limits, concurrency |
| `TransportResult` / `TransportException` | Result and error types (no payload data leaked) |

## Security properties
- HTTPS only, port 443 only, no credentials, no fragments
- DNS resolved and validated at **connection time** (not registration time)
- Every DNS answer checked; mixed public/private answers rejected (defends against DNS rebinding)
- Connection pinned to first validated IPv4 address (fallback to first address)
- Original hostname preserved in `Host` header for TLS SNI/hostname verification
- No redirects (`disableRedirectHandling`)
- No cookies (`disableCookieManagement`)
- No automatic retries (`disableAutomaticRetries`)
- No auth caching (`disableAuthCaching`)
- No connection reuse (`setConnectionReuseStrategy` returns `false`)
- Bounded connect/response timeouts
- Bounded response-body consumption (default 8 KiB, max 1 MiB)
- Reserved/transport headers rejected; only `webhook-id`, `webhook-timestamp`, `webhook-signature` allowed
- TLS hostname/certificate verification failures treated as permanent security failures

## Verification evidence

Commands run from repository root unless noted.

| Check | Command/evidence | Outcome |
|---|---|---|
| Toolchain | `./backend/gradlew -p backend --no-daemon --version` | Java 25.0.5; Gradle 9.9.1 |
| Build, tests, SBOM | `./backend/gradlew -p backend --no-daemon clean check bootJar cyclonedxBom` | **Passed**; 234 tests, zero failures/errors/skips |
| Dependency scan | `./gradlew -p backend dependencyCheckAnalyze --rerun-tasks` | **Passed**; 20 findings against `dependency-management-plugin-1.1.7.jar`, all MEDIUM/LOW (CVSS 2.6–6.8), zero High/Critical (≥7.0). All are false positives: the Gradle build plugin is misidentified as `spring_framework:1.1.7`; none of the CVEs apply to a Gradle plugin. `failBuildOnCVSS = 7.0` correctly allows the build. |
| Scan without NVD key | `env -u NVD_API_KEY ./gradlew -p backend dependencyCheckAnalyze --rerun-tasks` | **Passed**: same 20 findings; no credential-dependent bypass |
| Image build | `docker build --progress=plain -t hookrelay-review:local backend` | **Passed** |
| Image scan | `scripts/scan-containers.sh` | **Passed**: 0 image vulnerabilities, 0 High/Critical, 0 secret findings |
| Secret scan | Included in `scripts/scan-containers.sh` | **Passed**: 0 secret findings |

### Transport-specific test results (Step 3)

| Test class | Tests | Failures | Errors |
|---|---|---|---|
| `SsrfSafeWebhookTransportTest` | 5 | 0 | 0 |
| `SsrfAddressValidatorTest` | 12 | 0 | 0 |
| `DnsPinningIntegrationTest` | 3 | 0 | 0 |
| **Step 3 subtotal** | **20** | **0** | **0** |

### Related SSRF security tests (pre-existing, re-validated)

| Test class | Tests | Failures | Errors |
|---|---|---|---|
| `DefaultSsrfUrlValidatorTest` | 23 | 0 | 0 |
| `DnsPolicyTest` | 3 | 0 | 0 |

### Full build test results

| Suite | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| All backend tests | 234 | 0 | 0 | 0 |

## Official API sources reviewed
- [Apache HttpClient 5 DNS resolver](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/DnsResolver.html)
- [Spring Framework HttpComponents request factory](https://docs.spring.io/spring-framework/docs/7.0.10/javadoc-api/org/springframework/http/client/HttpComponentsClientHttpRequestFactory.html)
- [PostgreSQL 18 error codes](https://www.postgresql.org/docs/18/errcodes-appendix.html) (SQLSTATE classification for transient vs permanent failures)
- [RabbitMQ queue limits and overflow policies](https://www.rabbitmq.com/docs/maxlength)

## Notes
- No new application runtime dependency version was introduced beyond Apache HttpClient 5, managed by the Spring Boot BOM.
- `WebhookTransportProperties` uses `@ConfigurationProperties("hookrelay.delivery.transport")` with sensible defaults; no environment variable overrides are required for operation.
- The transport is not yet wired into the delivery dispatcher; it is available for Step 5 (dispatch/retry) to use.
- Outbound delivery remains disabled by default; no worker is enabled by this step.
