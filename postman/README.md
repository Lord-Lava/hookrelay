# HookRelay — Postman API Collections & Smoke Testing

This directory contains a production-quality Postman testing setup for HookRelay:

- **Comprehensive API collection** — `HookRelay API.postman_collection.json`
- **Dedicated smoke-test collection** — `HookRelay Smoke Tests.postman_collection.json`
- **Development environment** — `environments/HookRelay Development.postman_environment.json`
- **Production environment** — `environments/HookRelay Production.postman_environment.json`

## Repository Structure

```
postman/
├── HookRelay API.postman_collection.json          # Comprehensive API test collection
├── HookRelay Smoke Tests.postman_collection.json  # Dedicated smoke-test suite
├── environments/
│   ├── HookRelay Development.postman_environment.json
│   └── HookRelay Production.postman_environment.json
└── README.md                                       # This file
```

## Implemented API Coverage

The collections cover only the HTTP endpoints that are actually implemented in the HookRelay backend:

| Endpoint | Method | Status | Covered |
|---|---|---|---|
| `GET /actuator/health` | GET | 200 | Yes (health check) |
| `POST /v1/events` | POST | 202 | Yes (event ingestion) |
| `POST /v1/endpoints` | POST | 201 | Yes (create endpoint) |
| `GET /v1/endpoints` | GET | 200 | Yes (list endpoints) |
| `GET /v1/endpoints/{id}` | GET | 200 | Yes (get endpoint) |
| `PATCH /v1/endpoints/{id}` | PATCH | 200 | Yes (update endpoint) |
| `POST /v1/endpoints/{id}/enable` | POST | 200 | Yes (enable endpoint) |
| `POST /v1/endpoints/{id}/disable` | POST | 200 | Yes (disable endpoint) |
| `DELETE /v1/endpoints/{id}` | DELETE | 204 | Yes (soft delete endpoint) |

**Not yet implemented (pending future work):**
- Event-type subscriptions (`POST /v1/endpoints/{id}/subscriptions`)
- Delivery history and DLQ
- Event replay
- API key management
- Signing-secret management

## Authentication

HookRelay authenticates requests using an `X-Api-Key` header. The auth filter applies to all `/v1/*` paths. The health endpoint (`/actuator/health`) does not require authentication.

Set your API key in the environment variable `{{api_key}}`.

## Environment Setup

### 1. Import Environments

Import both environment files into Postman:

- `environments/HookRelay Development.postman_environment.json`
- `environments/HookRelay Production.postman_environment.json`

### 2. Configure Development Environment

Edit `HookRelay Development.postman_environment.json` and replace the placeholders:

| Variable | Example Value | Description |
|---|---|---|
| `base_url` | `http://localhost:8080` | Backend URL (default local Compose port) |
| `api_key` | `hr_live_xxxx...` | Valid project API key from your dev environment |
| `project_id` | `bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb` | UUID of the test project |
| `other_project_id` | `dddddddd-4444-4444-8444-dddddddddddd` | UUID of a different project (for isolation tests) |

### 3. Configure Production Environment

Edit `HookRelay Production.postman_environment.json` and replace the placeholders:

| Variable | Description |
|---|---|
| `base_url` | Production backend URL (e.g., `https://api.hookrelay.example.com`) |
| `api_key` | Production project API key (**treat as secret**) |
| `project_id` | Production test project UUID |
| `other_project_id` | Different project UUID (for isolation tests) |

**Never commit real credentials.** The committed files contain only placeholders:
- `PLACEHOLDER_DEV_API_KEY`
- `PLACEHOLDER_PRODUCTION_API_KEY`
- `PLACEHOLDER_PROJECT_ID`
- `PLACEHOLDER_OTHER_PROJECT_ID`
- `PLACEHOLDER_PRODUCTION_BASE_URL`

## How to Run Tests

### Option A: Postman Desktop App

1. Import the collection: `HookRelay API.postman_collection.json` or `HookRelay Smoke Tests.postman_collection.json`
2. Import the environment: `HookRelay Development.postman_environment.json`
3. Select the environment in the top-right dropdown
4. Run the collection using the Collection Runner

### Option B: Postman CLI (Newman)

Install Newman:

```bash
npm install -g newman
```

Run the smoke tests against development:

```bash
newman run "postman/HookRelay Smoke Tests.postman_collection.json" \
  -e "postman/environments/HookRelay Development.postman_environment.json" \
  --reporters cli,json \
  --reporter-json-export smoke-results.json
```

Run the comprehensive API collection against development:

```bash
newman run "postman/HookRelay API.postman_collection.json" \
  -e "postman/environments/HookRelay Development.postman_environment.json" \
  --reporters cli,json \
  --reporter-json-export api-results.json
```

### Option C: npm script (if added to package.json)

```bash
npm run test:smoke        # Run smoke tests
npm run test:api          # Run comprehensive API tests
```

## Idempotency & Test Data Isolation

The smoke test collection is designed to be safe for repeated execution:

1. **Deterministic test data** — All smoke test resources use fixed identifiers:
   - Endpoint URL: `https://smoke-test.example.com/webhook`
   - Event idempotency key: `smoke-event-001`
   - Event type: `payment.succeeded`

2. **Existing resource detection** — If the smoke test endpoint already exists (from a previous run), the collection resolves its ID by listing endpoints and matching the test URL, rather than creating a duplicate.

3. **Idempotency keys** — Event ingestion uses a fixed idempotency key (`smoke-event-001`). Replaying the same key returns the same event with `Idempotent-Replayed: true` instead of creating a duplicate.

4. **No destructive cleanup** — The smoke tests do not delete the test endpoint after running. This allows you to inspect the created resource and ensures the collection is safe to re-run. The endpoint is disabled at the end of the CRUD sequence.

5. **Collection variables** — Test resource IDs (`smokeEndpointId`, `smokeEventId`) are stored in collection variables and reused across requests within the same run.

## Production Execution Precautions

- **Do not run comprehensive tests against production** without explicit authorization.
- The smoke test collection is non-destructive but still creates resources (endpoint, event) in the target environment.
- Production smoke tests must use a dedicated test project and explicitly provisioned test credentials.
- Avoid secret rotation, resource deletion, and destructive state transitions against production.
- If required production test configuration is missing, tests fail safely (401/404) rather than falling back to development credentials.

## Test Scenarios Covered

### Smoke Tests (14 requests)

**Health Check:**
- Application health returns 200 with status UP

**Authentication:**
- Valid API key creates endpoint (201) or finds existing (409)
- Missing authentication rejected (401)

**Endpoint CRUD:**
- List endpoints returns 200 with project-scoped results
- Get endpoint by ID returns 200 with expected fields
- Update endpoint returns 200 with updated description and enabled state
- Enable endpoint returns 200
- Disable endpoint returns 200

**Event Ingestion:**
- Successful event ingestion returns 202 Accepted
- Idempotency replay returns same eventId with `Idempotent-Replayed: true`

**Tenant Isolation:**
- Cross-project access denied (404, no information leakage)

**Invalid Request Handling:**
- Blank URL rejected (400 with validation errors)
- Non-HTTPS URL rejected (400 with validation errors)

### Comprehensive API Collection (~45 requests)

**Health Check:**
- Application health check (200, status UP)

**Event Ingestion (9 requests):**
- Successful event ingestion (202)
- Idempotency replay: same key, same payload (202, `Idempotent-Replayed: true`)
- Idempotency conflict: same key, different payload (409)
- Missing required fields (400)
- Invalid event type format (400)
- Data not a JSON object (400)
- Missing authentication (401)
- Invalid API key (401)

**Endpoint Management (22 requests):**
- Create endpoint successfully (201)
- Blank URL rejected (400)
- Non-HTTPS URL rejected (400)
- URL too long rejected (400)
- Description too long rejected (400)
- Missing authentication (401)
- Invalid API key (401)
- Duplicate URL rejected (409)
- List endpoints successfully (200)
- Invalid page (negative) (400)
- Invalid size zero (400)
- Invalid size exceeds 100 (400)
- Missing authentication for list (401)
- Get existing endpoint (200)
- Nonexistent endpoint (404)
- Cross-project access denied (404)
- Update endpoint successfully (200)
- Update with duplicate URL rejected (409)
- Nonexistent endpoint update (404)
- Enable endpoint (200)
- Disable endpoint (200)
- Delete endpoint soft delete (204)
- Delete nonexistent endpoint (404)

**Tenant Isolation (1 request):**
- Cross-project endpoint access denied (404)

## Response Format

All API errors return RFC 9457 `ProblemDetail` responses with `type`, `title`, `status`, `detail`, and `timestamp` fields. Validation failures include an `errors` map of field-to-message.

Successful event ingestion returns `AcceptedEventResponse` with `eventId`, `createdAt`, `status`, plus response headers `Idempotent-Replayed` and `Cache-Control: no-store`.

## Signing Secret Safety

No test asserts or logs endpoint signing secrets. The response DTO (`EndpointResponse`) deliberately excludes `signingSecretCiphertext`. Tests verify this field is absent from all responses.

## Verification Checklist

Before committing changes:

- [ ] Both collections import successfully into Postman Desktop
- [ ] Both environments import successfully
- [ ] Variables resolve correctly (`{{base_url}}`, `{{api_key}}`, `{{project_id}}`)
- [ ] Authentication works with a valid API key
- [ ] Test ordering is correct (health → auth → CRUD → events → isolation → invalid)
- [ ] Tests are repeatable (run smoke collection twice consecutively)
- [ ] No real credentials are committed (only placeholders)
- [ ] No destructive production requests are executed
- [ ] All supported scenarios pass against development

## Known Limitations

- **Newman/Postman CLI not yet installed** — Install with `npm install -g newman` before running CLI-based tests
- **No live backend running** — The collections cannot be executed until a HookRelay backend is running at the configured `base_url`
- **Production tests not executed** — Per safety policy, no tests have been run against production
- **Subscription API not covered** — Endpoint subscription management is not yet implemented in the backend
