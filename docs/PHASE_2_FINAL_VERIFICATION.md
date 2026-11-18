# Phase 2 verification — 2026-11-17

## Follow-up verification — 2026-11-18

The provenance verifier now handles Gradle distributions. Credential-dependent SCA
bypasses introduced after the original review have been removed; advisory updates,
CVSS >= 7.0 failures and scanner-error failures are unconditional again.

- `python3 -m unittest discover -s scripts -p 'test_*.py'`: five regression tests passed.
- `python3 scripts/verify-dependency-provenance.py --report /tmp/hookrelay-fixed-provenance.json`:
  all 1,104 committed artifact entries matched official sources.
- From `backend/`, `./gradlew --no-daemon clean check bootJar cyclonedxBom` passed.
  The test task restored cached results: 158 tests, zero failures/errors/skips.
- From `backend/`, `./gradlew --no-daemon dependencyCheckAnalyze --rerun-tasks` and
  `env -u NVD_API_KEY ./gradlew --no-daemon dependencyCheckAnalyze --rerun-tasks`
  both failed on the existing 44 advisory matches across eight components.
- A temporary Gradle init task checked the evaluated scanner settings with an NVD
  key present and absent: CVSS threshold 7.0, failure on scanner errors, updates
  enabled and the optional key correctly bound without logging its value.
- Docker was unavailable. Container integration tests were not freshly executed;
  Compose smoke tests and image/secret scans were not rerun. The historical image
  scan findings below remain unresolved. These changes do not constitute release approval.

## Original verification

The application checks pass. Release approval remains blocked by dependency scan
findings and incomplete production controls. Passing compilation/tests is not a
claim that the application or its dependencies are vulnerability-free.

## Corrections implemented

- CI runs `--version` separately from build tasks; the build command now executes
  tests, formatting/import checks, packaging and SBOM generation.
- Dependency-Check always fails on CVSS >= 7.0 and scanner errors, updates its
  advisory database, and uses `NVD_API_KEY` when supplied. Tests and build-plugin
  dependencies are included. Missing credentials never relax the threshold.
- Trivy uses a persistent host cache and a 20-minute image-scan deadline. CI restores
  and saves the cache, including after failures; database updates remain enabled.
  Secret scanning runs independently before image scanning. Either failure makes
  the script fail, and old reports are removed before execution.
- Docker copies application, settings and buildscript lockfiles, preserves strict
  dependency verification and packages with `bootJar`. Docker-dependent tests run
  on the host/CI runner before image construction.
- Testcontainers modules and core are all Boot-managed 2.0.5. The OpenAPI contract
  is parsed and checked against controller operations, DTO fields and input limits.
  HTTP security tests use an actual embedded servlet server and HTTP client.

## Evidence

Commands below run from the repository root unless noted. Generated reports stay
under `backend/build/reports/`, outside version control.

| Check | Command/evidence | Outcome |
|---|---|---|
| Toolchain | `./backend/gradlew -p backend --no-daemon --version` | Java 25.0.5; Gradle 9.9.1 |
| Build, tests, SBOM | `./backend/gradlew -p backend --no-daemon clean check bootJar cyclonedxBom` | Passed; 154 tests, zero failures/errors/skips. Some task outputs restored from Gradle's build cache. |
| Collection syntax | `node postman/check-collections.mjs` | Passed; 75 requests' JSON bodies and scripts checked. |
| Dependency scan | `./backend/gradlew -p backend dependencyCheckAnalyze --rerun-tasks` | Failed as required: 44 reported vulnerability matches across eight components, including shaded components. NVD data updated successfully. |
| Scan without key | `env -u NVD_API_KEY ./backend/gradlew -p backend dependencyCheckAnalyze --rerun-tasks` | Failed on the same 44 matches at the same CVSS threshold; no credential-dependent bypass. |
| Scanner failure handling | Temporary Docker stub exercised success, secret failure, image failure and image-export failure | All four scenarios passed; either failed check returns nonzero and neither masks the other. |
| Image build | `docker build --progress=plain -t hookrelay-review:local backend` | Passed. |
| Image and secret scans | `scripts/scan-containers.sh` | Completed; zero secret findings, zero High/Critical Ubuntu package findings, four High Java findings. Exit 1 correctly blocks release. |

The earlier review on the same day additionally verified these unchanged application
artifacts/contracts:

- `python3 scripts/verify-compose.py --image hookrelay-review:local --newman newman`:
  isolated image smoke passed; Newman smoke 23 requests/43 assertions and comprehensive
  52 requests/103 assertions, zero failures. This runner performs one smoke run,
  not two; repeatability is not inferred from a single run.
- `python3 scripts/verify-dependency-provenance.py`: 920/920 artifact checks matched
  official repository checksums/downloads. See `dependency-review.md` for method and
  limits; provenance verification is separate from vulnerability analysis.

## Dependency findings requiring triage

The current report does **not** establish 44 distinct exploitable application flaws.
Dependency-Check reported these matches while scanning the complete build/test graph:

| Component | Matches |
|---|---:|
| dependency-management-plugin 1.1.7 | 31 |
| docker-java-transport-zerodep 3.7.1: shaded httpclient5 5.5.1 | 2 |
| same artifact: shaded httpcore5-h2 5.3.6 | 2 |
| same artifact: shaded httpcore5 5.3.6 | 2 |
| httpclient5-cache 5.6.2 | 2 |
| kotlin-stdlib-jdk7 1.9.10 | 2 |
| kotlin-stdlib-jdk8 1.9.10 | 2 |
| spring-boot-loader-tools 4.1.2 | 1 |

Several matches warrant component-identity review: for example, the dependency
management plugin was identified as Spring Framework. These are not suppressed or
declared false positives without advisory-specific review. The scan also reported
that its .NET assembly analyzer could not initialize because the required runtime
was unavailable, and that Sonatype OSS Index credentials were unavailable. Consult
both the HTML/JSON reports and console output when assessing coverage.

Reports: `backend/build/reports/dependency-check/dependency-check-report.{html,json}`.
CI uploads this directory even on failure. Confirm affected ranges and dependency
paths, upgrade supported components where needed, and document narrowly scoped,
expiring exceptions only after review. The build remains red for these findings.

## Image findings requiring review

Trivy 0.76.2 reported four High advisories against the Boot-managed
`com.rabbitmq:amqp-client:5.31.0` in the application image:

| Advisory | Fixed version reported by Trivy |
|---|---|
| CVE-2026-63337 | 5.33.0 |
| CVE-2026-69219 | 5.33.1 |
| CVE-2026-69220 | 5.33.1 |
| CVE-2026-75516 | 5.34.0 |

These scanner-reported fixes require vendor advisory and Spring Boot/BOM compatibility
review before changing the approved dependency baseline. No suppressions or dependency
version overrides were added in this verification repair. Release remains blocked.
Reports: `backend/build/reports/trivy-image.json` and `trivy-secrets.json`.
The cold scan completed after downloading the Java database; a second run against
the newly rebuilt image reused the persistent databases without downloading them again.

## Remaining deployment requirements

- Resolve/triage dependency and image findings and scanner coverage errors before release.
- Hosted GitHub Actions and CodeQL execution have not been verified locally. Their
  workflow configuration is committed; configure `NVD_API_KEY` as a repository secret
  and make the verification jobs required branch checks in repository settings.
- KMS/envelope encryption, retention/backup policies and deployment-level network
  controls remain production requirements described in `architecture.md`/`AGENTS.md`.
- Outbound delivery, connection-time SSRF enforcement, retries and the customer DLQ
  remain future capabilities, not functionality verified by these checks.

## Running the scanners

```sh
./backend/gradlew -p backend dependencyCheckAnalyze --rerun-tasks
scripts/scan-containers.sh
```

Trivy caches under `${XDG_CACHE_HOME:-$HOME/.cache}/hookrelay/trivy`; override with
`HOOKRELAY_TRIVY_CACHE_DIR` if needed. The image name is `hookrelay-review:local`.
Cold database downloads can take several minutes. A timeout, stale/unavailable
advisory data or scanner failure is inconclusive, never a zero-vulnerability result.
Gradle dependency verification is automatic; there is no `dependencyVerification`
task. Do not generate new lockfiles/checksums as a routine verification command.
