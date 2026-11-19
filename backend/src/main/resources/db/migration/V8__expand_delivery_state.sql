-- These V1 tables have no application writer before Phase 3. Historical destinations
-- cannot be reconstructed from today's endpoint URL. Stop for operator review rather
-- than silently fabricate history or schedule legacy rows. No data is deleted.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM deliveries) OR EXISTS (SELECT 1 FROM delivery_attempts) THEN
        RAISE EXCEPTION 'Phase 3 requires an operator-reviewed backfill for existing delivery history';
    END IF;
END $$;

ALTER TABLE deliveries
    ADD COLUMN project_id UUID,
    ADD COLUMN destination_url TEXT,
    ADD COLUMN lease_owner VARCHAR(80),
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN terminal_at TIMESTAMPTZ,
    ADD COLUMN last_error_code VARCHAR(80),
    ADD COLUMN cycle_number INTEGER,
    ADD COLUMN cycle_attempt_count INTEGER,
    ADD COLUMN cycle_started_at TIMESTAMPTZ,
    ADD COLUMN cycle_deadline TIMESTAMPTZ,
    ADD COLUMN policy_version VARCHAR(32),
    ADD COLUMN max_attempts INTEGER;

ALTER TABLE delivery_attempts
    ADD COLUMN project_id UUID,
    ADD COLUMN cycle_number INTEGER,
    ADD COLUMN lease_token UUID,
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN outcome VARCHAR(24),
    ADD COLUMN error_category VARCHAR(32);
