CREATE TABLE identity.connected_sources
(
    id                       UUID PRIMARY KEY,
    user_id                  UUID        NOT NULL,
    provider                 TEXT        NOT NULL,           -- enum as string: 'STRAVA', …
    provider_user_id         TEXT        NOT NULL,           -- e.g. strava_athlete_id
    access_token             BYTEA       NOT NULL,           -- AES-GCM ciphertext (nonce||ct||tag)
    refresh_token            BYTEA       NOT NULL,           -- AES-GCM ciphertext
    access_token_expires_at  TIMESTAMPTZ NOT NULL,
    refresh_token_expires_at TIMESTAMPTZ NULL,
    scopes                   TEXT[]      NOT NULL DEFAULT '{}',
    status                   TEXT        NOT NULL,           -- 'ACTIVE' | 'REVOKED' | 'ERROR'
    failure_count            INT         NOT NULL DEFAULT 0,
    last_error               TEXT NULL,
    last_refreshed_at        TIMESTAMPTZ NULL,
    last_used_at             TIMESTAMPTZ NULL,
    version                  BIGINT      NOT NULL DEFAULT 0, -- @Version optimistic lock
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_connected_sources_user
        FOREIGN KEY (user_id) REFERENCES identity.users (id),

    -- One identity per (provider, external id). Resolves login to an existing source.
    CONSTRAINT uq_connected_sources_provider_identity
        UNIQUE (provider, provider_user_id)
);

-- At most one NON-revoked source per (user, provider), while still allowing
-- a fresh row after a revoke (reconnect). Partial unique index.
CREATE UNIQUE INDEX uq_connected_sources_active_per_user_provider
    ON identity.connected_sources (user_id, provider) WHERE status <> 'REVOKED';

-- Hot lookup: resolve a user's active Strava source (TokenManager, ingestion).
CREATE INDEX ix_connected_sources_user_provider
    ON identity.connected_sources (user_id, provider);

-- Reconciliation / proactive refresh sweeps: find ACTIVE sources by expiry.
CREATE INDEX ix_connected_sources_status_expiry
    ON identity.connected_sources (status, access_token_expires_at);
