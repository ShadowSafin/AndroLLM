-- AndroLLM Identity Foundation — Phase 1 schema (PostgreSQL)
-- Private backend only. No secrets in this file — safe to keep as reference
-- in the public repo. Apply with: psql "$DATABASE_URL" -f schema-identity.sql
--
-- Phase 1: identity only (users table). No events/sessions/devices yet (Phase 3).
-- web_connected columns are included for forward compatibility but MUST stay
-- false until Phase 2 explicit consent flips them.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    firebase_uid    TEXT NOT NULL UNIQUE,
    email           TEXT,
    display_name    TEXT,
    photo_url       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    sync_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    settings        JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- Phase 2 consent flags (default false in Phase 1; enforced by API, not just DDL)
    web_connected   BOOLEAN NOT NULL DEFAULT FALSE,
    web_connected_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_users_last_seen_at ON users (last_seen_at DESC);

-- Safety: firebase_uid must never be empty.
ALTER TABLE users
    ADD CONSTRAINT users_firebase_uid_nonempty CHECK (firebase_uid <> '');

COMMENT ON TABLE users IS 'Phase 1 identity: one row per Firebase UID (canonical key = firebase_uid).';
COMMENT ON COLUMN users.firebase_uid IS 'Verified Firebase UID from Admin SDK verifyIdToken(). Never client-supplied.';
COMMENT ON COLUMN users.sync_enabled IS 'Phase 1 always false. Phase 2/3 enable only after explicit Connect Web Dashboard consent.';
COMMENT ON COLUMN users.web_connected IS 'Phase 2 consent flag. Phase 1 backend never sets it true.';
