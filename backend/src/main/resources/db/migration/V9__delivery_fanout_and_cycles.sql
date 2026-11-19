CREATE TABLE event_fanouts (
    project_id UUID NOT NULL,
    event_id UUID NOT NULL,
    snapshot_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    recipient_count INTEGER NOT NULL CHECK (recipient_count BETWEEN 0 AND 1000),
    body_version VARCHAR(32) NOT NULL CHECK (body_version = 'v1'),
    body BYTEA NOT NULL CHECK (octet_length(body) BETWEEN 2 AND 270336),
    PRIMARY KEY (project_id, event_id)
);

CREATE TABLE delivery_cycles (
    project_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    cycle_number INTEGER NOT NULL CHECK (cycle_number >= 0),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deadline TIMESTAMPTZ NOT NULL,
    policy_version VARCHAR(32) NOT NULL CHECK (policy_version ~ '^[A-Za-z0-9_-]{1,32}$'),
    max_attempts INTEGER NOT NULL CHECK (max_attempts BETWEEN 1 AND 100),
    terminal_status VARCHAR(24),
    terminal_at TIMESTAMPTZ,
    replay_key VARCHAR(128),
    replay_fingerprint CHAR(64),
    actor_api_key_id UUID,
    reason VARCHAR(160),
    PRIMARY KEY (project_id, delivery_id, cycle_number),
    CHECK (deadline > started_at),
    CHECK ((terminal_status IS NULL AND terminal_at IS NULL)
        OR (terminal_status IS NOT NULL AND terminal_status IN ('DELIVERED', 'DEAD_LETTERED', 'CANCELLED')
            AND terminal_at IS NOT NULL AND terminal_at >= started_at)),
    CHECK ((cycle_number = 0 AND replay_key IS NULL AND replay_fingerprint IS NULL
            AND actor_api_key_id IS NULL AND reason IS NULL)
        OR (cycle_number > 0 AND replay_key IS NOT NULL AND replay_key ~ '^[A-Za-z0-9._:-]{1,128}$'
            AND replay_fingerprint IS NOT NULL AND replay_fingerprint ~ '^[a-f0-9]{64}$'
            AND actor_api_key_id IS NOT NULL AND reason IS NOT NULL AND length(reason) > 0))
);
CREATE UNIQUE INDEX uq_delivery_cycle_replay ON delivery_cycles(project_id, replay_key)
    WHERE replay_key IS NOT NULL;

-- Reserved for future fleet-wide dispatch admission. No scheduler is enabled here.
CREATE TABLE project_dispatch_controls (
    project_id UUID PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    window_started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    starts INTEGER NOT NULL DEFAULT 0 CHECK (starts >= 0),
    last_served_at TIMESTAMPTZ
);
CREATE TABLE endpoint_dispatch_controls (
    project_id UUID NOT NULL,
    endpoint_id UUID NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    starts INTEGER NOT NULL DEFAULT 0 CHECK (starts >= 0),
    last_served_at TIMESTAMPTZ,
    PRIMARY KEY (project_id, endpoint_id)
);
