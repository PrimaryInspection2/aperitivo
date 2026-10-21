-- Test-only mirror of db/migration/__root/V1__create_event_publication_table.sql.
--
-- In production, Spring Modulith's per-module Flyway strategy runs the "__root"
-- location against the public schema separately from each BC's location, so
-- that migration and identity/V1 can both legitimately be numbered V1. In the
-- IT context we run Boot's single Flyway instance against the identity
-- location only (to avoid a duplicate-V1 collision), and instead materialise
-- the outbox table here as V999 — a version production migrations never reach,
-- so the next production V5/V6 cannot collide with it (V4 once did).
--
-- If you change db/migration/__root/V1__create_event_publication_table.sql,
-- mirror the DDL here or the itest context will fail with:
--   ERROR: relation "event_publication" does not exist
CREATE TABLE event_publication
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
