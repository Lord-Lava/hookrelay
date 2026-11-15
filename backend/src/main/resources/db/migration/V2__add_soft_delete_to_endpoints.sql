-- V2__add_soft_delete_to_endpoints.sql
ALTER TABLE endpoints ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_endpoints_project_active
    ON endpoints (project_id)
    WHERE deleted_at IS NULL;

ALTER TABLE endpoint_subscriptions ADD COLUMN deleted_at TIMESTAMPTZ;

-- Drop the full unique constraint so soft-deleted rows don't block re-subscription
ALTER TABLE endpoint_subscriptions DROP CONSTRAINT IF EXISTS endpoint_subscriptions_endpoint_id_event_type_key;

CREATE UNIQUE INDEX idx_active_endpoint_subscription
    ON endpoint_subscriptions (endpoint_id, event_type)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_endpoint_subscriptions_endpoint_active
    ON endpoint_subscriptions (endpoint_id)
    WHERE deleted_at IS NULL;
