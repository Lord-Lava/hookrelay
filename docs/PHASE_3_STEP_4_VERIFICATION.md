# Phase 3 Step 4 — HMAC-SHA256 webhook signing protocol verification

## Verification date
2026-11-20

## What was implemented
Commit `7967110` ("feat(delivery): add Phase 3 Step 4 HMAC-SHA256 webhook signing protocol") merged into `main`, along with fix commit `831d886` ("fix(delivery): harden signing key length, body signing bytes, and transport header allowlist").

The signing infrastructure implements the Standard Webhooks v1 HMAC-SHA256 wire construction. Key components:

| Component | Responsibility |
|---|---|
| `WebhookSigner` | Signing contract interface |
| `HmacSha256WebhookSigner` | HMAC-SHA256 implementation |
| `SsrfSafeWebhookTransport.send(url, body, headers)` | Accepts optional signing headers (`webhook-id`, `webhook-timestamp`, `webhook-signature`) |

## Signing protocol
**Signed message:** `webhook-id.webhook-timestamp.rawBodyBytes`
**Signature format:** `v1,<standard-padded-base64(HMAC-SHA256(key, message))>`

**Key derivation:**
1. Strip `whsec_` prefix if present
2. Base64url-decode the remainder
3. Result must be exactly 32 bytes (256 bits)
4. Reject invalid base64url or wrong key length

**HTTP headers added to outbound requests:**
- `webhook-id`: lowercase canonical delivery UUID
- `webhook-timestamp`: decimal Unix epoch seconds
- `webhook-signature`: `v1,<base64 signature>`

## Security properties
- HMAC-SHA256 (not a home-grown algorithm)
- Constant-time key derivation and signing
- 32-byte key minimum enforced (rejects weak keys)
- Base64url secret decoding (matches HookRelay's existing `whsec_` secret format)
- Thread-local `Mac` instances (no shared mutable state)
- Key cache (`ConcurrentHashMap`) avoids re-decoding on every sign
- Null/blank inputs rejected with `IllegalArgumentException`
- Signing headers are the only non-reserved headers allowed by the transport; reserved headers (`Host`, `Authorization`, `Cookie`, etc.) are rejected
- No secret material in exception messages or logs

## Verification evidence

Commands run from repository root unless noted.

| Check | Command/evidence | Outcome |
|---|---|---|
| Toolchain | `./backend/gradlew -p backend --no-daemon --version` | Java 25.0.5; Gradle 9.9.1 |
| Build, tests, SBOM | `./backend/gradlew -p backend --no-daemon clean check bootJar cyclonedxBom` | **Passed**; 234 tests, zero failures/errors/skips |
| Dependency scan | `./gradlew -p backend dependencyCheckAnalyze --rerun-tasks` | **Passed**; 20 findings against `dependency-management-plugin-1.1.7.jar`, all MEDIUM/LOW, zero High/Critical (≥7.0). All are false positives (plugin misidentified as Spring Framework). |
| Image scan | `scripts/scan-containers.sh` | **Passed**: 0 image vulnerabilities, 0 High/Critical, 0 secret findings |

### Step 4 test results

| Test class | Tests | Failures | Errors |
|---|---|---|---|
| `HmacSha256WebhookSignerTest` | 14 | 0 | 0 |

### Step 4 test coverage

| Coverage area | Tested |
|---|---|
| Valid signing with `whsec_` prefix | Yes |
| Valid signing without `whsec_` prefix | Yes |
| Deterministic signatures for identical inputs | Yes |
| Different secrets produce different signatures | Yes |
| Different messages produce different signatures | Yes |
| Invalid base64url secret rejected | Yes |
| Null/blank inputs rejected | Yes |
| Key must decode to exactly 32 bytes | Yes |

### Full build test results

| Suite | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| All backend tests | 234 | 0 | 0 | 0 |

## Official API sources reviewed
- [Standard Webhooks specification](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md)
- [RFC 9110 Retry-After](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3) (for related timestamp handling)
- [Java Cryptography Architecture](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/crypto/Mac.html) (HMAC-SHA256)

## Notes
- Signing is not yet wired into the delivery dispatcher; Step 5 (dispatch/retry) will invoke the signer when constructing outbound requests.
- The webhook signing secret is separate from the signing encryption key (`hookrelay.signing.encryption-key`); the webhook secret is provided at endpoint creation/rotation time via `whsec_`-prefixed plaintext.
- No KMS or dual-signing overlap is implemented; immediate secret replacement semantics apply (see Phase 3 architecture document).
- Outbound delivery remains disabled by default; no worker is enabled by this step.
