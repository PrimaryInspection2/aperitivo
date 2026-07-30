CREATE TABLE identity.users
(
    id           UUID PRIMARY KEY,
    display_name TEXT        NOT NULL,
    email        TEXT NULL,
    avatar_url   TEXT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);