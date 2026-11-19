-- V8 explicitly rejects legacy delivery history. Never infer its destination or
-- recipient snapshot. Existing projects/events/endpoints themselves need no backfill.
ALTER TABLE events ADD CONSTRAINT uq_events_project_id UNIQUE (project_id, id);
ALTER TABLE endpoints ADD CONSTRAINT uq_endpoints_project_id UNIQUE (project_id, id);
ALTER TABLE api_keys ADD CONSTRAINT uq_api_keys_project_id UNIQUE (project_id, id);
ALTER TABLE deliveries ADD CONSTRAINT uq_deliveries_project_id UNIQUE (project_id, id);

ALTER TABLE deliveries
    ALTER COLUMN project_id SET NOT NULL,
    ALTER COLUMN destination_url SET NOT NULL,
    ALTER COLUMN cycle_number SET NOT NULL,
    ALTER COLUMN cycle_attempt_count SET NOT NULL,
    ALTER COLUMN cycle_started_at SET NOT NULL,
    ALTER COLUMN cycle_deadline SET NOT NULL,
    ALTER COLUMN policy_version SET NOT NULL,
    ALTER COLUMN max_attempts SET NOT NULL,
    ADD CONSTRAINT fk_deliveries_project FOREIGN KEY (project_id) REFERENCES projects(id),
    ADD CONSTRAINT fk_deliveries_event_project FOREIGN KEY (project_id, event_id) REFERENCES events(project_id, id),
    ADD CONSTRAINT fk_deliveries_endpoint_project FOREIGN KEY (project_id, endpoint_id) REFERENCES endpoints(project_id, id),
    ADD CONSTRAINT ck_delivery_status CHECK (status IN ('PENDING','IN_PROGRESS','RETRY_SCHEDULED','DELIVERED','DEAD_LETTERED','CANCELLED')),
    ADD CONSTRAINT ck_delivery_counts CHECK (attempt_count >= 0 AND cycle_number >= 0
        AND cycle_attempt_count >= 0 AND cycle_attempt_count <= attempt_count
        AND max_attempts BETWEEN 1 AND 100 AND cycle_attempt_count <= max_attempts),
    ADD CONSTRAINT ck_delivery_policy CHECK (policy_version ~ '^[A-Za-z0-9_-]{1,32}$'
        AND cycle_deadline > cycle_started_at),
    ADD CONSTRAINT ck_delivery_destination CHECK (destination_url LIKE 'https://%'
        AND octet_length(destination_url) BETWEEN 9 AND 2000),
    ADD CONSTRAINT ck_delivery_lease CHECK (
        (status = 'IN_PROGRESS' AND lease_owner IS NOT NULL AND length(lease_owner) > 0
            AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'IN_PROGRESS' AND lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)),
    ADD CONSTRAINT ck_delivery_due CHECK (
        (status IN ('PENDING','RETRY_SCHEDULED') AND next_attempt_at IS NOT NULL AND next_attempt_at <= cycle_deadline)
        OR (status NOT IN ('PENDING','RETRY_SCHEDULED') AND next_attempt_at IS NULL)),
    ADD CONSTRAINT ck_delivery_terminal CHECK (
        (status IN ('DELIVERED','DEAD_LETTERED','CANCELLED') AND terminal_at IS NOT NULL)
        OR (status NOT IN ('DELIVERED','DEAD_LETTERED','CANCELLED') AND terminal_at IS NULL));

ALTER TABLE delivery_cycles
    ADD CONSTRAINT fk_cycle_delivery FOREIGN KEY (project_id, delivery_id) REFERENCES deliveries(project_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_cycle_actor FOREIGN KEY (project_id, actor_api_key_id) REFERENCES api_keys(project_id, id);
-- Defer the circular reference until commit so a delivery and cycle 0 are atomic.
ALTER TABLE deliveries ADD CONSTRAINT fk_delivery_current_cycle
    FOREIGN KEY (project_id, id, cycle_number) REFERENCES delivery_cycles(project_id, delivery_id, cycle_number)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE event_fanouts ADD CONSTRAINT fk_fanout_event
    FOREIGN KEY (project_id, event_id) REFERENCES events(project_id, id) ON DELETE CASCADE;
ALTER TABLE endpoint_dispatch_controls ADD CONSTRAINT fk_endpoint_control_project
    FOREIGN KEY (project_id, endpoint_id) REFERENCES endpoints(project_id, id) ON DELETE CASCADE;

ALTER TABLE delivery_attempts
    ALTER COLUMN project_id SET NOT NULL,
    ALTER COLUMN cycle_number SET NOT NULL,
    ALTER COLUMN lease_token SET NOT NULL,
    ALTER COLUMN started_at SET NOT NULL,
    ALTER COLUMN outcome SET NOT NULL,
    ADD CONSTRAINT fk_attempt_delivery_project FOREIGN KEY (project_id, delivery_id) REFERENCES deliveries(project_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_attempt_cycle FOREIGN KEY (project_id, delivery_id, cycle_number) REFERENCES delivery_cycles(project_id, delivery_id, cycle_number) ON DELETE CASCADE,
    ADD CONSTRAINT ck_attempt_number CHECK (attempt_number > 0 AND cycle_number >= 0),
    ADD CONSTRAINT ck_attempt_status CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    ADD CONSTRAINT ck_attempt_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    ADD CONSTRAINT ck_attempt_response CHECK (response_body IS NULL),
    ADD CONSTRAINT ck_attempt_outcome CHECK (outcome IN ('STARTED','SUCCEEDED','RETRYABLE_FAILURE','PERMANENT_FAILURE','UNKNOWN','ABORTED')),
    ADD CONSTRAINT ck_attempt_completion CHECK (
        (outcome = 'STARTED' AND completed_at IS NULL AND http_status IS NULL AND duration_ms IS NULL)
        OR (outcome <> 'STARTED' AND completed_at IS NOT NULL AND completed_at >= started_at)),
    ADD CONSTRAINT ck_attempt_success CHECK (outcome <> 'SUCCEEDED' OR (http_status IS NOT NULL AND http_status BETWEEN 200 AND 299)),
    ADD CONSTRAINT ck_attempt_unknown CHECK (outcome NOT IN ('UNKNOWN','ABORTED') OR http_status IS NULL);
