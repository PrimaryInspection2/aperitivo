CREATE TABLE identity.user_identities
(
    id               UUID PRIMARY KEY,
    user_id          UUID        NOT NULL,
    provider         TEXT        NOT NULL, -- enum as string: 'GOOGLE', …
    provider_user_id TEXT        NOT NULL, -- e.g. google_sub_id
    last_login_at    TIMESTAMPTZ NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_user_identities_user
        FOREIGN KEY (user_id) REFERENCES identity.users (id),

    -- 1 aperitivo user per Identity provider (e.g. Google, Facebook, etc.)
    CONSTRAINT uq_user_identities_user_id_provider
        UNIQUE (user_id, provider),

    -- 1 Identity provider id per Provider (e.g. each new Google account = new user)
    CONSTRAINT uq_user_identities_provider_user_id_provider
        UNIQUE (provider, provider_user_id)

);