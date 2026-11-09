CREATE TABLE identity.refresh_tokens
(
    token_hash               TEXT        PRIMARY KEY,
    user_id                  UUID        NOT NULL,
    family_id                UUID        NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,
    family_created_at        TIMESTAMPTZ NOT NULL, -- fix edge case when user log in every day, so we cant identify first issued token (for 30 days cleanup) bcs of cleanup job (14 days)
    expires_at               TIMESTAMPTZ NOT NULL,
    used_at                  TIMESTAMPTZ,
    revoked_at               TIMESTAMPTZ,

    CONSTRAINT fk_refresh_token_user
        FOREIGN KEY (user_id) REFERENCES identity.users (id)
);

-- One token cannot be used twice in scope of 1 family. Saves us from race condition during token updates (parallel
-- /refresh from 2 browser tabs) + makes it impossible to insert 2 unused tokens directly to DB.

CREATE UNIQUE INDEX uq_refresh_tokens_single_token_use_per_family
    ON identity.refresh_tokens (family_id) WHERE used_at IS NULL AND revoked_at IS NULL;

-- We will use it for revoke tokens per user per family or for all user's families (logout, reuse active token, etc).
-- Because we also have index above, but it does not serve all needed rows (bcs condition)
CREATE  INDEX ix_refresh_tokens_user_id_family_id
    ON identity.refresh_tokens (user_id, family_id);


