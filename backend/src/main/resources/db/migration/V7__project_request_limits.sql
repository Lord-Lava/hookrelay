-- One row per project, shared by every application instance; no attacker-controlled bucket keys.
CREATE TABLE project_request_limits (
    project_id UUID PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    window_end TIMESTAMPTZ NOT NULL,
    requests INTEGER NOT NULL CHECK (requests > 0)
);
