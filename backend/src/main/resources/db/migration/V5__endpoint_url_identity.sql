-- Keep path and query case. Normalize only scheme, DNS host and default HTTPS port.
-- Existing equivalent URLs cause the new index to fail rather than deleting customer data.
DROP INDEX idx_endpoints_project_url_active;
UPDATE endpoints
SET url = 'https://' || lower(regexp_replace(regexp_replace(
        substring(url from '(?i)^https://([^/?#]+)'), ':443$', ''), '\.$', '')) ||
    CASE
        WHEN substring(url from '(?i)^https://[^/?#]+(.*)$') = '' THEN '/'
        WHEN substring(url from '(?i)^https://[^/?#]+(.*)$') LIKE '?%'
          THEN '/' || substring(url from '(?i)^https://[^/?#]+(.*)$')
        ELSE substring(url from '(?i)^https://[^/?#]+(.*)$')
    END;
CREATE UNIQUE INDEX idx_endpoints_project_url_active
    ON endpoints (project_id, url) WHERE deleted_at IS NULL;
