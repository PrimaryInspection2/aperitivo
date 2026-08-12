CREATE TABLE identity.user_identities
(
    id               UUID PRIMARY KEY,
    user_id          UUID        NOT NULL,
    provider         TEXT        NOT NULL, -- enum as string: 'GOOGLE', …
    provider_user_id TEXT        NOT NULL, -- e.g. google_sub_id
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_user_identities_user
        FOREIGN KEY (user_id) REFERENCES identity.users (id)
);