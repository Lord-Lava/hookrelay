CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(120) NOT NULL,
    environment VARCHAR(16) NOT NULL CHECK (environment IN ('development', 'staging', 'production')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name, environment)
);

CREATE INDEX idx_projects_organization ON projects (organization_id);

CREATE TABLE api_keys (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    name VARCHAR(80) NOT NULL,
    key_prefix VARCHAR(32) NOT NULL UNIQUE,
    key_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_api_keys_project ON api_keys (project_id);

CREATE TABLE endpoints (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    url TEXT NOT NULL,
    description VARCHAR(160),
    signing_secret_ciphertext TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_endpoints_project_enabled ON endpoints (project_id, enabled);

CREATE TABLE endpoint_subscriptions (
    id UUID PRIMARY KEY,
    endpoint_id UUID NOT NULL REFERENCES endpoints(id) ON DELETE CASCADE,
    event_type VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (endpoint_id, event_type)
);

CREATE TABLE events (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    idempotency_key VARCHAR(128),
    request_fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_events_project_idempotency
    ON events (project_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_events_project_created ON events (project_id, created_at DESC);
CREATE INDEX idx_events_project_type_created ON events (project_id, event_type, created_at DESC);

CREATE TABLE outbox_messages (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    aggregate_id UUID NOT NULL,
    message_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED')),
    publish_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ,
    locked_by VARCHAR(80),
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_claimable ON outbox_messages (status, available_at, created_at)
    WHERE status <> 'PUBLISHED';
CREATE INDEX idx_outbox_aggregate ON outbox_messages (aggregate_id);

CREATE TABLE deliveries (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id),
    endpoint_id UUID NOT NULL REFERENCES endpoints(id),
    status VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, endpoint_id)
);

CREATE INDEX idx_deliveries_due ON deliveries (status, next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED');

CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    http_status INTEGER,
    response_body TEXT,
    error_code VARCHAR(80),
    duration_ms BIGINT,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (delivery_id, attempt_number)
);
