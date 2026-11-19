-- Empty delivery tables are enforced by V8; ordinary transactional index creation
-- is intentional. Reassess concurrent builds before adapting this to populated data.
DROP INDEX idx_deliveries_due;
CREATE INDEX idx_deliveries_due ON deliveries(project_id, next_attempt_at, created_at, id)
    WHERE status IN ('PENDING','RETRY_SCHEDULED');
CREATE INDEX idx_deliveries_expired_lease ON deliveries(project_id, lease_expires_at, id)
    WHERE status = 'IN_PROGRESS';
CREATE INDEX idx_deliveries_history ON deliveries(project_id, status, created_at DESC, id DESC);
CREATE INDEX idx_deliveries_endpoint_active ON deliveries(project_id, endpoint_id, id)
    WHERE status IN ('PENDING','RETRY_SCHEDULED','IN_PROGRESS');
CREATE INDEX idx_subscriptions_type_active ON endpoint_subscriptions(event_type, endpoint_id)
    WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_attempt_started ON delivery_attempts(project_id, delivery_id)
    WHERE outcome = 'STARTED';
CREATE INDEX idx_attempts_project_delivery ON delivery_attempts(project_id, delivery_id, attempt_number);
