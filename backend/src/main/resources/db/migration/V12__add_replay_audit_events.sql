-- Phase 3 Step 7: Manual delivery replay audit trail.
-- Replay audit records are append-only and stored separately from the transport outbox.
-- No plaintext secrets, authorization headers, or payload data are recorded.

CREATE TABLE delivery_replay_audit_events (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    delivery_id UUID NOT NULL,
    previous_cycle_number INTEGER NOT NULL CHECK (previous_cycle_number >= 0),
    new_cycle_number INTEGER NOT NULL CHECK (new_cycle_number > 0 AND new_cycle_number > previous_cycle_number),
    actor_api_key_id UUID NOT NULL REFERENCES api_keys(id),
    idempotency_key_fingerprint CHAR(64) NOT NULL CHECK (idempotency_key_fingerprint ~ '^[a-f0-9]{64}$'),
    action VARCHAR(32) NOT NULL CHECK (action = 'REPLAYED'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_replay_audit_project_created
    ON delivery_replay_audit_events (project_id, created_at DESC);
CREATE INDEX idx_delivery_replay_audit_delivery
    ON delivery_replay_audit_events (delivery_id, created_at DESC);
