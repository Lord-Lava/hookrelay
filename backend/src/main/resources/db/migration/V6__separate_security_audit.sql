CREATE TABLE endpoint_audit_events (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    endpoint_id UUID NOT NULL REFERENCES endpoints(id),
    action VARCHAR(32) NOT NULL CHECK (action = 'SECRET_ROTATED'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_endpoint_audit_project_created ON endpoint_audit_events (project_id, created_at);

-- Preserve historical audit records while removing non-ingestion work from the transport outbox.
INSERT INTO endpoint_audit_events (id, project_id, endpoint_id, action, created_at)
SELECT id, project_id, aggregate_id, message_type, created_at
FROM outbox_messages WHERE message_type = 'SECRET_ROTATED';
DELETE FROM outbox_messages WHERE message_type = 'SECRET_ROTATED';
ALTER TABLE outbox_messages ADD CONSTRAINT outbox_ingestion_message_type
    CHECK (message_type = 'EVENT_INGESTED');

-- Existing unsigned endpoints are retained but disabled. Rotation provisions their first secret.
UPDATE endpoints SET enabled = FALSE
WHERE signing_secret_ciphertext = '' OR signing_secret_nonce IS NULL OR octet_length(signing_secret_nonce) <> 12 OR signing_secret_key_version IS NULL;
ALTER TABLE endpoints ADD CONSTRAINT enabled_endpoint_has_secret CHECK (
    NOT enabled OR (signing_secret_ciphertext <> '' AND octet_length(signing_secret_nonce) = 12
                    AND signing_secret_nonce IS NOT NULL AND signing_secret_key_version IS NOT NULL)
);
