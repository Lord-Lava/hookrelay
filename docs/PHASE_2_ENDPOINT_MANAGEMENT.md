# HookRelay — Phase 2: Endpoint Management & Security

## 1. Objective

Build the functionality that allows a customer to register and manage the destinations to which HookRelay will eventually deliver webhooks.

At the end of this phase, a customer should be able to:

1. Authenticate using their project API key.
2. Register multiple webhook endpoints.
3. View, update, enable, disable, and delete their endpoints.
4. Subscribe endpoints to specific event types.
5. Manage endpoint signing secrets.
6. Rotate signing secrets without exposing existing secrets.
7. Be prevented from accessing another customer's resources.
8. Be prevented from registering dangerous or malicious destination URLs.

**Important scope boundary:** This phase does not implement actual webhook delivery. No HTTP POST requests should be sent to registered endpoints yet. That belongs to Phase 3.

---

# 2. Expected architecture

The conceptual flow will be:

```text
                  Customer Application
                          |
                          | Project API Key
                          v
                  HookRelay Backend
                          |
                   Authentication
                          |
                   Tenant Resolution
                          |
               Endpoint Management API
                          |
             +------------+------------+
             |                         |
             v                         v
       PostgreSQL                 Security Layer
             |                         |
       +-----+------+             +----+------+
       |            |             |           |
       v            v             v           v
   Endpoints   Subscriptions   URL Validation  Secret Encryption
       |
       v
  Future Delivery Engine
      (Phase 3)
```

The backend remains a modular monolith. Endpoint management, subscription management, authentication, and security should be separate logical modules, not separate microservices.

## 2.1 Core concepts

| Concept        | Meaning                                                        |
| -------------- | -------------------------------------------------------------- |
| Organization   | A customer or company using HookRelay                          |
| Project        | A logical application/environment belonging to an organization |
| API key        | Credential used by a customer application to authenticate      |
| Endpoint       | A destination URL that receives webhook events                 |
| Subscription   | Association between an endpoint and an event type              |
| Signing secret | Secret used to authenticate webhook requests in the future     |

An organization may own multiple projects. Each project may own multiple endpoints. Each endpoint may subscribe to multiple event types.

For the initial implementation, endpoint management should be scoped to a project.

---

# 3. Database design

Before writing migrations, inspect the existing schema and reuse existing organization, project, and API-key tables. Do not introduce duplicate representations of concepts that already exist.

The following is the proposed logical schema.

## 3.1 `webhook_endpoints`

Stores registered destination endpoints.

| Column                   | Type         | Constraints            | Description                    |
| ------------------------ | ------------ | ---------------------- | ------------------------------ |
| id                       | UUID         | PK                     | Endpoint identifier            |
| project_id               | UUID         | FK, NOT NULL           | Owning project                 |
| name                     | VARCHAR(100) | NOT NULL               | Customer-defined endpoint name |
| url                      | TEXT         | NOT NULL               | Destination URL                |
| signing_secret_encrypted | TEXT         | NOT NULL               | Encrypted signing secret       |
| secret_key_version       | VARCHAR(50)  | NOT NULL               | Encryption key version         |
| enabled                  | BOOLEAN      | NOT NULL, DEFAULT TRUE | Whether endpoint is active     |
| created_at               | TIMESTAMPTZ  | NOT NULL               | Creation time                  |
| updated_at               | TIMESTAMPTZ  | NOT NULL               | Last update time               |
| deleted_at               | TIMESTAMPTZ  | NULL                   | Soft deletion timestamp        |

Suggested constraints and indexes:

- Foreign key to the existing projects table.
- Index on `(project_id, created_at)`.
- Index on `(project_id, enabled)`.
- Validate URL length and format in the application.
- Consider a uniqueness constraint only if product requirements establish that endpoint URLs must be unique within a project.

**Design decision:** Use soft deletion initially. This preserves historical references for future delivery logs and audit records. Soft-deleted endpoints must not be selectable for future delivery.

Do not store signing secrets in plaintext.

## 3.2 `endpoint_subscriptions`

Stores the event types an endpoint wants to receive.

| Column      | Type         | Constraints  | Description             |
| ----------- | ------------ | ------------ | ----------------------- |
| id          | UUID         | PK           | Subscription identifier |
| endpoint_id | UUID         | FK, NOT NULL | Associated endpoint     |
| event_type  | VARCHAR(150) | NOT NULL     | Event type              |
| created_at  | TIMESTAMPTZ  | NOT NULL     | Creation time           |

Constraints:

```sql
UNIQUE (endpoint_id, event_type)
```

Indexes:

```sql
CREATE INDEX idx_endpoint_subscriptions_endpoint
ON endpoint_subscriptions(endpoint_id);

CREATE INDEX idx_endpoint_subscriptions_event_type
ON endpoint_subscriptions(event_type);
```

Examples:

```text
payment.succeeded
payment.failed
refund.created
customer.updated
```

Event types should be treated as exact identifiers. Do not silently introduce wildcard matching unless it is explicitly designed and tested.

## 3.3 Future considerations

Do not implement these tables in Phase 2 unless the existing project already requires them:

- `delivery_attempts`
- `webhook_deliveries`
- `dead_letter_deliveries`
- `endpoint_secret_rotations`
- `endpoint_health`

These belong primarily to later delivery and observability phases.

If you need an audit trail for secret rotation or endpoint changes, introduce an appropriately scoped audit mechanism rather than storing historical plaintext secrets.

---

# 4. API design

Use consistent REST conventions and the existing API versioning and response conventions in the codebase.

Suggested base path:

```text
/api/v1
```

All endpoints below require project-level authentication.

## 4.1 Endpoint management

### Create endpoint

`POST /api/v1/endpoints`

Request:

```json
{
  "name": "Production Payments",
  "url": "https://api.example.com/webhooks"
}
```

Response — `201 Created`:

```json
{
  "id": "endpoint-uuid",
  "name": "Production Payments",
  "url": "https://api.example.com/webhooks",
  "enabled": true,
  "createdAt": "2026-11-15T10:00:00Z",
  "signingSecret": "whsec_generated_secret"
}
```

The signing secret is shown only when created. It must never be returned by subsequent endpoint retrieval or listing operations.

The API should make this one-time disclosure clear in its documentation.

### List endpoints

`GET /api/v1/endpoints`

Example response:

```json
{
  "data": [
    {
      "id": "endpoint-uuid",
      "name": "Production Payments",
      "url": "https://api.example.com/webhooks",
      "enabled": true,
      "createdAt": "2026-11-15T10:00:00Z"
    }
  ],
  "hasMore": false
}
```

Requirements:

- Only return endpoints belonging to the authenticated project.
- Support pagination.
- Never expose encrypted or decrypted secrets.
- Exclude soft-deleted endpoints by default.

### Get endpoint

`GET /api/v1/endpoints/{endpointId}`

Return endpoint metadata and its subscriptions.

### Update endpoint

`PATCH /api/v1/endpoints/{endpointId}`

Request:

```json
{
  "name": "Production Payment Events",
  "url": "https://api.example.com/new-webhook-path"
}
```

Requirements:

- Both fields should be optional.
- Validate a changed URL using the same URL security policy as endpoint creation.
- Do not modify the signing secret during an ordinary metadata update.
- Reject attempts to update an endpoint owned by another project.

### Enable endpoint

`POST /api/v1/endpoints/{endpointId}/enable`

### Disable endpoint

`POST /api/v1/endpoints/{endpointId}/disable`

Disabling an endpoint should prevent it from being selected for future deliveries once the delivery engine is implemented.

### Delete endpoint

`DELETE /api/v1/endpoints/{endpointId}`

Recommended response:

`204 No Content`

Use soft deletion. The endpoint should no longer appear in normal listing or be eligible for future delivery.

---

## 4.2 Subscription management

Subscriptions determine which event types an endpoint receives.

### Add subscription

`PUT /api/v1/endpoints/{endpointId}/subscriptions/{eventType}`

Example:

```text
PUT /api/v1/endpoints/123/subscriptions/payment.succeeded
```

Response:

`204 No Content`

Using PUT makes the operation naturally idempotent.

### Remove subscription

`DELETE /api/v1/endpoints/{endpointId}/subscriptions/{eventType}`

Response:

`204 No Content`

### List subscriptions

`GET /api/v1/endpoints/{endpointId}/subscriptions`

Response:

```json
{
  "data": [
    {
      "eventType": "payment.succeeded"
    },
    {
      "eventType": "payment.failed"
    }
  ]
}
```

Requirements:

- Prevent duplicate subscriptions.
- Validate event-type syntax and maximum length.
- Enforce endpoint ownership before modifying subscriptions.
- A subscription must never be attachable to an endpoint from another project.

---

## 4.3 Signing secret management

### Rotate signing secret

`POST /api/v1/endpoints/{endpointId}/rotate-secret`

Response:

```json
{
  "signingSecret": "whsec_new_generated_secret",
  "createdAt": "2026-11-15T10:00:00Z"
}
```

Requirements:

- Generate secrets using a cryptographically secure random generator.
- Return the new plaintext secret only once.
- Encrypt the new secret before persisting it.
- Never include secret values in logs or error responses.
- Make the rotation operation transactional.
- Record an audit event without recording the secret itself.

**Rotation policy decision:** Start with immediate replacement of the previous secret. Supporting overlapping old and new secrets requires additional state and verification semantics. Add that later if customer migration requirements justify it.

---

# 5. Security requirements

Security is a core part of this phase, not an optional enhancement.

## 5.1 Tenant isolation

Every operation must be scoped to the authenticated project.

The authenticated project must come from the verified API-key identity, not from a customer-supplied `projectId` in the request body.

Preferred repository pattern:

```java
Optional<WebhookEndpoint> findByIdAndProjectId(
    UUID endpointId,
    UUID projectId
);
```

Avoid:

```java
Optional<WebhookEndpoint> findById(UUID endpointId);
```

followed by relying on the caller to remember to check ownership.

Apply the same rule to:

- Endpoint retrieval
- Endpoint updates
- Endpoint deletion
- Enable/disable operations
- Subscription management
- Secret rotation

Use a consistent not-found response for resources that do not belong to the authenticated project. This avoids exposing whether another tenant's resource exists.

If organization-level authorization is required elsewhere, enforce it through the existing project-to-organization relationship.

## 5.2 Endpoint URL validation and SSRF protection

\<badge color="danger">Critical security requirement\</badge>

HookRelay will eventually make outbound HTTP requests to customer-supplied URLs. This creates a Server-Side Request Forgery (SSRF) risk.

A malicious customer could register URLs targeting internal infrastructure, cloud metadata services, or private services.

Examples of destinations that must not be reachable:

```text
http://127.0.0.1
http://localhost
http://169.254.169.254
http://10.0.0.5
http://192.168.1.10
http://[::1]
http://[fc00::1]
```

### Registration-time validation

At minimum:

1. Only permit `https` by default.
2. Reject URL credentials such as `https://user:pass@example.com`.
3. Reject localhost and local hostnames.
4. Reject loopback, private, link-local, multicast, unspecified, and reserved IP ranges.
5. Validate IPv4 and IPv6 addresses.
6. Reject malformed URLs and unsupported ports.
7. Normalize and validate hostnames carefully.
8. Do not trust user-supplied DNS results or textual hostname checks alone.

If local HTTP is needed for development, provide an explicit development-only configuration. Do not weaken production validation.

### Connection-time validation

Registration-time checks are insufficient.

An attacker may register a public hostname that initially resolves to a public IP but later resolves to a private IP. This is DNS rebinding.

Before the delivery engine makes an outbound connection:

1. Resolve the hostname.
2. Validate all resolved IP addresses.
3. Ensure the actual connection uses an address that passed validation.
4. Avoid a second uncontrolled DNS resolution between validation and connection.
5. Revalidate every redirect destination, or disable redirects entirely.
6. Enforce connection and response timeouts.
7. Limit response size.
8. Restrict outbound network access at the infrastructure level where possible.

**Important:** A URL validator that only runs during endpoint creation does not fully solve SSRF. Phase 2 must establish the security design and reusable validation components. Phase 3 must enforce them at the actual network connection boundary.

### Redirect policy

For the first delivery-engine implementation, prefer disabling automatic redirects.

If redirects are later supported, each redirect must be treated as a new untrusted destination and undergo the complete SSRF validation process.

## 5.3 Signing secret generation

Generate secrets using Java's secure random APIs, for example `SecureRandom`.

Requirements:

- Sufficient entropy: target at least 256 bits.
- Use a recognizable prefix such as `whsec_`.
- Encode securely using URL-safe Base64 or another suitable encoding.
- Never use UUIDs, timestamps, ordinary random numbers, or predictable identifiers as secrets.
- Never log the plaintext secret.

## 5.4 Signing secret encryption

The signing secret must be encrypted at rest.

Recommended initial design:

- AES-256-GCM authenticated encryption.
- A unique random nonce for each encryption operation.
- Encryption keys supplied through a secure configuration or secret-management system.
- Key version stored alongside the encrypted value.
- Authentication tag verified during decryption.

Do not hardcode encryption keys in the repository.

Do not use a single application-wide hardcoded key in `application.yml`.

Design the encryption interface so the implementation can later move to a KMS-backed solution without changing endpoint business logic.

Suggested abstraction:

```java
public interface SecretEncryptionService {

    EncryptedSecret encrypt(String plaintext);

    String decrypt(EncryptedSecret encryptedSecret);
}
```

`EncryptedSecret` should contain the ciphertext, nonce, and key version as appropriate.

The encryption service should not expose secrets through `toString()`, logging, or exception messages.

## 5.5 API-key authentication

Reuse the authentication implementation from Phase 1.

Ensure:

- API keys are not stored as plaintext.
- Authentication identifies the correct project.
- Invalid and revoked keys are rejected.
- Authentication failures use the existing consistent Problem Details response format.
- Secrets and API keys are redacted from logs.

Do not introduce an alternative authentication mechanism just for endpoint management.

---

# 6. Suggested backend structure

Follow the existing package conventions in the repository. Do not reorganize unrelated modules just to match this illustration.

One possible logical structure:

```text
endpoint/
├── api/
│   ├── EndpointController.java
│   ├── SubscriptionController.java
│   ├── EndpointSecretController.java
│   ├── request/
│   └── response/
│
├── application/
│   ├── EndpointService.java
│   ├── SubscriptionService.java
│   └── EndpointSecretService.java
│
├── domain/
│   ├── WebhookEndpoint.java
│   ├── EndpointSubscription.java
│   └── EndpointStatus.java
│
├── persistence/
│   ├── WebhookEndpointRepository.java
│   └── EndpointSubscriptionRepository.java
│
└── security/
    ├── EndpointUrlValidator.java
    ├── SecretEncryptionService.java
    └── SigningSecretGenerator.java
```

Responsibilities:

| Component           | Responsibility                                      |
| ------------------- | --------------------------------------------------- |
| Controller          | HTTP contract, request validation, response mapping |
| Application service | Business workflows and transaction boundaries       |
| Domain              | Endpoint and subscription rules                     |
| Repository          | Database persistence                                |
| URL validator       | URL parsing and destination policy                  |
| Secret generator    | Secure random secret generation                     |
| Encryption service  | Encrypt/decrypt signing secrets                     |

Avoid putting business logic in controllers or making repositories responsible for authorization decisions.

---

# 7. Sequential implementation plan

Implement the following tasks in order. Each task should be reviewed and tested before moving to the next.

## Step 0 — Inspect existing architecture

Before writing code:

- Read `AGENTS.md`.
- Read `docs/HOOKRELAY_OVERVIEW.md`.
- Inspect existing organization, project, API-key, authentication, and exception-handling implementations.
- Inspect database migration conventions.
- Inspect existing tests and test infrastructure.
- Identify the existing API response and pagination conventions.
- Verify the pinned Spring Boot, Spring Security, persistence, and cryptography APIs against official documentation.

Deliverable:

A short implementation proposal identifying which existing classes and tables will be reused, which migrations are needed, and any conflicts with this design.

Do not change code during this inspection step.

## Step 1 — Endpoint domain model and persistence

Implement:

- `WebhookEndpoint`
- Endpoint status or enabled-state handling
- `EndpointSubscription`
- Repository interfaces
- Database migrations
- Required constraints and indexes

Requirements:

- Use UUID identifiers consistently with the existing codebase.
- Use UTC-compatible timestamp handling.
- Enforce foreign-key relationships.
- Add project-scoped repository methods.
- Implement soft deletion.
- Keep persistence models consistent with existing conventions.

Tests:

- Endpoint persistence.
- Subscription persistence.
- Unique subscription constraint.
- Project ownership relationships.
- Soft deletion behavior.
- Migration execution against PostgreSQL.

Acceptance criteria:

A project can persist and retrieve its own endpoint records without accessing another project's records.

## Step 2 — Endpoint CRUD APIs

Implement:

- Create endpoint.
- List endpoints.
- Get endpoint.
- Update endpoint.
- Enable endpoint.
- Disable endpoint.
- Delete endpoint.

Initially, the URL validation component can enforce basic URL syntax and scheme rules. Do not treat that as complete SSRF protection.

Tests:

- Successful endpoint creation.
- Invalid URL rejection.
- Missing required fields.
- Endpoint listing and pagination.
- Update behavior.
- Enable/disable behavior.
- Soft deletion.
- Not-found behavior.
- Cross-project access rejection.

Acceptance criteria:

A customer can manage endpoints using their project API key, and cannot access endpoints owned by another project.

## Step 3 — Event subscriptions

Implement:

- Add subscription.
- Remove subscription.
- List subscriptions.
- Duplicate subscription handling.
- Event-type validation.

Tests:

- Adding a subscription.
- Duplicate PUT request.
- Removing a subscription.
- Removing a nonexistent subscription.
- Listing subscriptions.
- Cross-project subscription access.
- Invalid event types.

Acceptance criteria:

A project can associate endpoints with event types, and subscription changes cannot affect another project.

## Step 4 — Signing secret generation

Implement:

- Cryptographically secure secret generator.
- Recognizable secret format.
- One-time secret response.
- Secret redaction in DTOs and logs.

Tests:

- Correct secret format.
- Sufficient generated entropy.
- Different values across generated secrets.
- Secret absent from list and get responses.
- Secret absent from ordinary update responses.
- Secret absent from logs and exception output.

Acceptance criteria:

New endpoints receive secure signing secrets, but those secrets are disclosed only in the creation response.

## Step 5 — Secret encryption

Implement:

- Encryption abstraction.
- AES-256-GCM implementation.
- Nonce generation.
- Key-version handling.
- Secure configuration for encryption keys.
- Decryption for future delivery use.

Tests:

- Encrypt/decrypt round trip.
- Different ciphertexts for the same plaintext across encryption operations.
- Tampered ciphertext rejection.
- Tampered authentication tag rejection.
- Wrong-key rejection.
- Missing key configuration handling.
- No plaintext secret in persisted data.

Acceptance criteria:

The application can recover the signing secret when required, but the database never stores it in plaintext.

## Step 6 — Secret rotation

Implement:

- Rotation endpoint.
- New secret generation.
- Encryption and persistence.
- Transactional replacement.
- Audit event without secret material.

Tests:

- Successful rotation.
- Previous secret no longer active under the initial immediate-replacement policy.
- New secret is returned once.
- Secret cannot be retrieved through normal endpoint APIs.
- Rotation of another project's endpoint is rejected.
- Persistence failure does not leave a partially rotated secret.

Acceptance criteria:

A customer can replace an endpoint's signing secret without exposing it again through read APIs.

## Step 7 — SSRF protection foundation

Implement reusable URL security components.

Separate:

1. URL syntax validation.
2. Hostname and IP classification.
3. Destination policy.
4. Connection-time destination enforcement design.

Cover:

- IPv4 special-use and private ranges.
- IPv6 loopback, unique-local, link-local, multicast, and other disallowed ranges.
- Localhost and internal hostnames.
- IPv4-mapped IPv6 addresses.
- Alternate IP representations.
- Malformed and ambiguous URLs.
- Disallowed schemes and ports.
- DNS resolution returning prohibited addresses.

Do not build a fake guarantee by checking DNS only once during endpoint registration.

The delivery engine must later use a network client that enforces the policy at connection time.

Tests:

- Public HTTPS destination accepted.
- Localhost rejected.
- Private IPv4 rejected.
- Loopback IPv6 rejected.
- Link-local addresses rejected.
- Cloud metadata address rejected.
- Hostname resolving to private IP rejected by the relevant policy component.
- Mixed public and private DNS answers rejected.
- Malformed and unsupported URLs rejected.

Acceptance criteria:

The application has a reusable, thoroughly tested destination security policy, and the Phase 3 delivery design explicitly requires connection-time enforcement.

## Step 8 — Integration and regression testing

Use the existing Testcontainers setup if available.

Run integration tests against PostgreSQL rather than relying only on mocks.

Verify:

- Migrations work from an empty database.
- Endpoint CRUD works end-to-end.
- Subscription workflows work end-to-end.
- Project isolation works across real database queries.
- Secret encryption works with configured test keys.
- Secret rotation is transactional.
- Existing authentication and ingestion functionality still work.
- Existing outbox functionality remains unaffected.

Acceptance criteria:

The complete Phase 2 test suite passes without regressing Phase 1 functionality.

## Step 9 — Documentation and completion

Update:

- API documentation.
- Endpoint lifecycle documentation.
- Subscription documentation.
- Signing secret handling.
- SSRF threat model.
- Configuration documentation.
- Local development instructions.
- Example curl requests.

Document that endpoint registration does not currently trigger webhook delivery.

Run all relevant repository checks and report actual results.

---

# 8. Test strategy

Use three layers of tests.

## Unit tests

Test business rules without starting the application:

- Endpoint validation.
- Subscription rules.
- Secret generation.
- Encryption and decryption.
- Secret rotation logic.
- URL parsing and IP classification.

## Integration tests

Test actual Spring application and PostgreSQL behavior:

- Database migrations.
- CRUD operations.
- Project-scoped queries.
- Transaction boundaries.
- Unique constraints.
- Soft deletion.
- Secret persistence.
- Authentication.

## API/security tests

Test requests through the real security filter chain:

- Missing API key.
- Invalid API key.
- Revoked API key.
- Valid API key.
- Cross-project endpoint access.
- Cross-project subscription modification.
- Cross-project secret rotation.
- Consistent error responses.
- Sensitive data redaction.

Avoid relying exclusively on mocked repositories for tenant-isolation tests. The actual database query behavior is part of the security boundary.

---

# 9. Error response conventions

Reuse the existing RFC 9457 Problem Details implementation.

Examples of expected status codes:

| Scenario                           | HTTP status |
| ---------------------------------- | ----------: |
| Endpoint created                   |         201 |
| Endpoint retrieved                 |         200 |
| Endpoint updated                   |         200 |
| Endpoint deleted                   |         204 |
| Invalid request                    |         400 |
| Missing/invalid credentials        |         401 |
| Resource inaccessible              |         404 |
| Duplicate or conflicting operation |         409 |
| Unexpected server error            |         500 |

Example:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "The endpoint URL is invalid.",
  "instance": "/api/v1/endpoints"
}
```

Never include:

- Signing secrets.
- API keys.
- Encryption keys.
- Database credentials.
- Internal network addresses or sensitive infrastructure details.

---

# 10. Definition of done

Phase 2 is complete only when all of the following are true:

- [ ] Endpoint creation works.
- [ ] Endpoint listing works.
- [ ] Endpoint retrieval works.
- [ ] Endpoint updates work.
- [ ] Endpoints can be enabled and disabled.
- [ ] Endpoints can be soft-deleted.
- [ ] Event subscriptions can be added, removed, and listed.
- [ ] Duplicate subscriptions are prevented.
- [ ] Signing secrets are generated securely.
- [ ] Signing secrets are encrypted at rest.
- [ ] Signing secrets are returned only during creation or rotation.
- [ ] Signing secrets never appear in logs.
- [ ] Secret rotation works transactionally.
- [ ] Project-level tenant isolation is tested.
- [ ] SSRF validation components are implemented and tested.
- [ ] Connection-time SSRF enforcement is specified as mandatory for Phase 3.
- [ ] PostgreSQL integration tests pass.
- [ ] Existing Phase 1 functionality remains intact.
- [ ] API and security documentation is updated.
- [ ] All relevant repository verification commands pass.

---

# 11. Coding-agent prompts

Use these prompts one at a time. Do not ask the agent to implement the whole phase in one run.

## Prompt 1 — Architecture inspection

```text
You are working on HookRelay.

Before changing any code, inspect:

- AGENTS.md
- docs/HOOKRELAY_OVERVIEW.md
- Existing database migrations
- Organization and project models
- API-key authentication
- Existing API controllers and response conventions
- Existing exception handling
- Existing test infrastructure

We are starting Phase 2: Endpoint Management and Security.

Your task is to produce an implementation proposal for:
1. Webhook endpoint persistence.
2. Endpoint subscriptions.
3. Endpoint CRUD APIs.
4. Signing secret generation and encryption.
5. Secret rotation.
6. SSRF protection foundations.

Reuse existing abstractions and conventions wherever possible.

Do not create duplicate organization, project, or authentication models.

Do not modify code or migrations yet.

Identify:
- Existing classes to reuse.
- New classes required.
- Database migration requirements.
- API routes and DTOs.
- Security concerns.
- Testing strategy.
- Any conflicts between this plan and the actual codebase.

Verify uncertain framework APIs and dependency compatibility using official documentation.

Return a concise but technically detailed implementation proposal.
```

## Prompt 2 — Endpoint persistence and domain

```text
Implement Phase 2, Step 1 of HookRelay:
Endpoint domain model and persistence.

First inspect AGENTS.md, docs/HOOKRELAY_OVERVIEW.md, and the architecture inspection results.

Implement:
- WebhookEndpoint persistence.
- EndpointSubscription persistence.
- Database migrations.
- Project-scoped repository methods.
- Soft deletion.
- Appropriate foreign keys, constraints, and indexes.

Important:
- Reuse the existing project and organization models.
- Do not introduce duplicate identity models.
- Follow existing naming and timestamp conventions.
- Do not implement HTTP controllers yet.
- Do not implement webhook delivery.
- Do not store signing secrets in plaintext.

Add unit and PostgreSQL integration tests where appropriate.

Run the relevant formatting and verification commands.

At the end, report:
1. Files changed.
2. Migrations added.
3. Tests added.
4. Commands actually executed.
5. Exact test/build results.
6. Any outstanding issues.

Never claim a test passed unless you executed it.
```

## Prompt 3 — Endpoint CRUD APIs

```text
Implement Phase 2, Step 2 of HookRelay:
Endpoint CRUD APIs.

Inspect the existing API and authentication conventions first.

Implement:
- POST /api/v1/endpoints
- GET /api/v1/endpoints
- GET /api/v1/endpoints/{endpointId}
- PATCH /api/v1/endpoints/{endpointId}
- POST /api/v1/endpoints/{endpointId}/enable
- POST /api/v1/endpoints/{endpointId}/disable
- DELETE /api/v1/endpoints/{endpointId}

Requirements:
- Authenticate using the existing project API-key mechanism.
- Resolve project identity from authenticated credentials.
- Enforce tenant isolation at the repository query level.
- Do not trust project IDs supplied by clients.
- Use soft deletion.
- Validate request DTOs.
- Reuse existing Problem Details handling.
- Never expose signing secrets through ordinary read/update APIs.

Do not implement actual webhook delivery.

Add tests for CRUD behavior, invalid requests, pagination, soft deletion, and cross-project access.

Run relevant verification commands and report exact results.
```

## Prompt 4 — Subscription management

```text
Implement Phase 2, Step 3 of HookRelay:
Endpoint event subscriptions.

Implement:
- PUT /api/v1/endpoints/{endpointId}/subscriptions/{eventType}
- DELETE /api/v1/endpoints/{endpointId}/subscriptions/{eventType}
- GET /api/v1/endpoints/{endpointId}/subscriptions

Requirements:
- Scope every operation to the authenticated project.
- Prevent duplicate subscriptions using database constraints.
- Make PUT idempotent.
- Validate event-type syntax and length.
- Ensure subscriptions cannot be modified across projects.
- Follow existing response conventions.

Do not implement wildcard subscriptions or delivery fan-out.

Add unit and integration tests, including duplicate requests and cross-project access.

Run relevant verification commands and report exact results.
```

## Prompt 5 — Signing secret generation and encryption

```text
Implement Phase 2, Steps 4 and 5 of HookRelay:
Signing secret generation and encryption.

Requirements:
- Generate at least 256 bits of cryptographically secure random secret material.
- Use a recognizable whsec_ prefix.
- Implement AES-256-GCM authenticated encryption.
- Generate a unique nonce for each encryption operation.
- Store ciphertext, nonce, and key version as appropriate.
- Obtain encryption keys through secure external configuration.
- Do not hardcode encryption keys in source code or application configuration committed to Git.
- Design an abstraction that can later support KMS-backed encryption.
- Never log plaintext secrets.
- Never expose secrets through toString(), DTOs, or exception messages.

Return plaintext only in the endpoint creation response.
Normal endpoint reads must never expose secrets.

Add tests for:
- Secret generation.
- Encrypt/decrypt round trips.
- Nonce uniqueness.
- Ciphertext tampering.
- Authentication tag failures.
- Wrong keys.
- Missing configuration.
- Plaintext absence from persistence.

Inspect the existing project first and avoid unnecessary refactoring.

Run relevant verification commands and report exact results.
```

## Prompt 6 — Secret rotation

```text
Implement Phase 2, Step 6 of HookRelay:
Signing secret rotation.

Add:
POST /api/v1/endpoints/{endpointId}/rotate-secret

Requirements:
- Authenticate using the existing project API key.
- Enforce endpoint ownership.
- Generate a fresh cryptographically secure secret.
- Encrypt it before persistence.
- Perform replacement transactionally.
- Return the new plaintext secret only in this response.
- Never return the secret from subsequent GET or LIST requests.
- Never log the secret.
- Record an audit event without storing secret material.

For the initial version, use immediate replacement of the previous secret.

Add tests for successful rotation, unauthorized access, cross-project access, transactional failure, and response redaction.

Do not implement overlapping-secret verification or webhook delivery.

Run relevant verification commands and report exact results.
```

## Prompt 7 — SSRF protection foundations

```text
Implement Phase 2, Step 7 of HookRelay:
SSRF protection foundations.

This is a security-sensitive implementation.

First inspect the current URL handling and any existing HTTP client abstractions.

Implement reusable components for:
- URL parsing and normalization.
- Allowed scheme and port validation.
- Hostname validation.
- IPv4 address classification.
- IPv6 address classification.
- Private, loopback, link-local, multicast, reserved, and unspecified address rejection.
- IPv4-mapped IPv6 handling.
- DNS resolution policy.
- Rejection when any resolved destination violates the security policy.

Do not rely on registration-time validation as the only SSRF defense.

The Phase 3 HTTP delivery client must enforce validation at actual connection time and prevent DNS rebinding between validation and connection.

Redirects must be disabled initially or revalidated at every hop.

Add security tests for:
- localhost.
- IPv4 loopback.
- RFC1918 private ranges.
- Link-local addresses.
- IPv6 loopback.
- IPv6 unique-local.
- IPv4-mapped IPv6.
- Cloud metadata addresses.
- Malformed URLs.
- Unsupported schemes.
- DNS results containing prohibited IP addresses.
- Mixed public/private DNS results.

Use well-maintained, compatible libraries where appropriate. Verify current official documentation and compatibility before adding dependencies.

Do not claim that SSRF protection is complete for outbound delivery until the connection-time enforcement exists.

Run relevant verification commands and report exact results.
```

## Prompt 8 — Final integration and regression review

```text
Perform the Phase 2 integration and regression review for HookRelay.

Inspect the implementation against:
- AGENTS.md
- docs/HOOKRELAY_OVERVIEW.md
- docs/PHASE_2_ENDPOINT_MANAGEMENT.md

Verify:
1. Endpoint CRUD.
2. Endpoint subscriptions.
3. Project-level tenant isolation.
4. Signing secret generation.
5. Signing secret encryption.
6. Secret rotation.
7. URL security validation.
8. Database migrations.
9. API error handling.
10. Existing Phase 1 ingestion and transactional outbox behavior.

Add missing PostgreSQL integration and API/security tests.

Review for:
- Plaintext secret leakage.
- Cross-tenant data exposure.
- Insecure repository queries.
- Missing transaction boundaries.
- SQL constraints and migration correctness.
- Incorrect assumptions about SSRF protection.
- Deprecated framework APIs.
- Dependency vulnerabilities.
- Unnecessary abstractions and overengineering.

Run:
- ./gradlew spotlessApply
- ./gradlew clean check
- ./gradlew bootJar

Also run any configured dependency or security audit tasks.

If a command is unavailable or fails, report that honestly.

Do not modify unrelated modules merely to improve style.

Return a final report with:
- Completed acceptance criteria.
- Files and migrations changed.
- Tests added.
- Actual command results.
- Known limitations.
- Remaining blockers.
- Recommendation on whether Phase 2 is ready to close.
```

---

# 12. What comes after Phase 2?

Once this milestone is complete, the next phase is the actual delivery engine.

| Phase   | Objective                                                                           |
|---------|-------------------------------------------------------------------------------------|
| Phase 1 | Event ingestion, API-key authentication, PostgreSQL, transactional outbox, RabbitMQ |
| Phase 2 | Endpoint management, subscriptions, secrets, tenant isolation, SSRF foundations     |
| Phase 3 | Event fan-out, delivery workers, HTTP dispatch, HMAC signatures                     |
| Phase 4 | Retry scheduling, backoff, delivery attempts, DLQ, replay                           |
| Phase 5 | Observability, rate limiting, concurrency controls, reliability                     |
| Phase 6 | Next.js customer dashboard                                                          |
| Phase 7 | Usage metering, billing, deployment, documentation, SDKs                            |

## Final milestone

Phase 2 should leave HookRelay in this state:

A customer can authenticate, register multiple webhook endpoints, subscribe each endpoint to specific event types, manage its signing secrets, and perform all of these operations without accessing another customer's resources or registering destinations that violate HookRelay's security policy.

The delivery engine should be the next major engineering milestone—not part of this one.
