-- V3__add_unique_index_for_endpoint_urls.sql
-- Prevents duplicate endpoint URLs per project at the database level.
-- This closes a race condition where two concurrent create/update requests
-- could both pass the in-memory duplicate check and insert the same URL.
CREATE UNIQUE INDEX idx_endpoints_project_url_active
    ON endpoints (project_id, lower(url))
    WHERE deleted_at IS NULL;
