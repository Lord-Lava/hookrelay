-- V4__add_signing_secret_nonce_and_key_version.sql
-- Adds columns to store the AES-256-GCM nonce and key version for encrypted signing secrets.
-- These columns support secret rotation: key_version identifies which key encrypted the ciphertext.
ALTER TABLE endpoints
    ADD COLUMN signing_secret_nonce BYTEA;

ALTER TABLE endpoints
    ADD COLUMN signing_secret_key_version VARCHAR(32);

CREATE INDEX idx_endpoints_signing_key_version
    ON endpoints (signing_secret_key_version)
    WHERE signing_secret_nonce IS NOT NULL;
