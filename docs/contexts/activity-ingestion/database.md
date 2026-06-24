# Activity Ingestion — Database Schema

Physical schema for the Activity Ingestion Bounded Context: tables, columns, constraints, indexes, and migration notes. Derived directly from the [domain model](domain-model.md).

Conventions: snake_case identifiers; UUID primary keys; `timestamptz` for all instants (stored UTC); `jsonb` for raw provider bodies; migrations are Flyway per-BC changesets applied at startup.

## Schema ownership

Ingestion owns its own logical schema within the shared PostgreSQL instance. Other BCs never read these tables directly — they consume `ActivityIngestedEvent` / `ActivityDeletedEvent` or go through published interfaces ([ADR 0005](../../adr/0005-bounded-contexts.md)). Concretely this is the `ingestion` schema (or table prefix, depending on the migration tool's layout); the DDL below omits the schema qualifier for readability.

One cross-BC reference appears throughout: `user_id` points at IAM's `users.id`. Because the two BCs may live in separate schemas (and, later, separate databases), this is modeled as a **plain `UUID` column without a database-level foreign key** — referential integrity across a BC boundary is the application's responsibility, not the schema's. (Contrast with IAM's internal `connected_sources.user_id`, which *does* carry an FK because both tables are IAM-owned.) See the note under each table.

## Tables

### webhook_events

The durable inbox of incoming Strava webhook deliveries. Written on receipt, before the endpoint answers `200`.

```sql
CREATE TABLE webhook_events (
    id            UUID        PRIMARY KEY,
    raw_body      JSONB       NOT NULL,           -- verbatim webhook envelope
    aspect_type   TEXT        NOT NULL,           -- 'create' | 'update' | 'delete' (Strava's vocab)
    object_type   TEXT        NOT NULL,           -- 'activity' | 'athlete'
    object_id     BIGINT      NOT NULL,           -- strava activity id, or athlete id for deauth
    owner_id      BIGINT      NOT NULL,           -- strava athlete id (owner of the object)
    status        TEXT        NOT NULL DEFAULT 'RECEIVED',  -- RECEIVED | PROCESSED | FAILED | IGNORED
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Notes:
- **No unique constraint, by design.** Duplicate deliveries become duplicate rows — that is the honest forensic record of what Strava sent, and it keeps the receipt insert unconditionally cheap (never fails on a dup, so the `200` is never jeopardised). Deduplication is the *job's* concern, on `sync_jobs`, not the inbox's.
- `aspect_type` / `object_type` are `TEXT`, not native PG enums and **not** mapped to an application enum — they are Strava's vocabulary. An unexpected value lands as inspectable data rather than a deserialization failure. (Our own `status` is also `TEXT` but mirrors the application `WebhookStatus` enum via `@Enumerated(EnumType.STRING)`.)
- `object_id` / `owner_id` are `BIGINT`: Strava ids are numeric and comfortably exceed 32-bit.
- `raw_body` is `JSONB` so malformed-but-valid-JSON payloads are still queryable for forensics; the parsed columns are routing conveniences derived from it.

### sync_jobs

A unit of ingestion work, with retry semantics and a status machine. The sole locus of idempotency.

```sql
CREATE TABLE sync_jobs (
    id                   UUID        PRIMARY KEY,
    user_id              UUID        NOT NULL,     -- id-ref to IAM users.id (no cross-BC FK)
    provider             TEXT        NOT NULL,     -- 'STRAVA'
    provider_activity_id BIGINT      NOT NULL,     -- the Strava activity this job fetches
    aspect_type          TEXT        NOT NULL,     -- 'create' | 'update' | 'delete' (part of idem key)
    source               TEXT        NOT NULL,     -- 'WEBHOOK' | 'BACKFILL' | 'RECONCILIATION'
    status               TEXT        NOT NULL DEFAULT 'PENDING',  -- PENDING|IN_PROGRESS|COMPLETED|FAILED|DEAD
    attempt_count        INT         NOT NULL DEFAULT 0,
    next_attempt_at      TIMESTAMPTZ NULL,         -- backoff schedule for retries
    last_error           TEXT        NULL,
    version              BIGINT      NOT NULL DEFAULT 0,  -- @Version: guards the worker-claim race
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Notes:
- `user_id` is a **plain id-reference** to IAM, no DB-level FK (cross-BC boundary). Navigation to the user, when needed, is via IAM's published interface — but the hot path doesn't need it: `SyncJobWorker` passes `user_id` straight to `TokenManager.getValidAccessToken(userId)`.
- `(provider, provider_activity_id, aspect_type)` is the **idempotency key** — unique constraint below. `aspect_type` is in the key because `create`/`update`/`delete` are distinct facts about one activity, not redeliveries of one.
- `version` backs Hibernate `@Version`: two workers must not both claim the same `PENDING` row.
- `provider`, `source`, `status` are `TEXT` mirroring application enums (`@Enumerated(EnumType.STRING)`) — adding a provider or a status is an app change, no `ALTER TYPE`.

### raw_activity_payloads

The verbatim Strava `GET /activities/{id}` body. The archive that allows re-emitting `ActivityIngestedEvent` without re-fetching (saving rate-limit budget).

```sql
CREATE TABLE raw_activity_payloads (
    id                   UUID        PRIMARY KEY,
    user_id              UUID        NOT NULL,     -- id-ref to IAM users.id (no cross-BC FK)
    provider             TEXT        NOT NULL,
    provider_activity_id BIGINT      NOT NULL,
    payload              JSONB       NOT NULL,     -- verbatim Strava activity JSON
    fetched_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Notes:
- One payload per successfully-fetched activity: unique on `(provider, provider_activity_id)` (below). A **failed job produces no row** — and a **`delete`-aspect job produces no row** (there is nothing to fetch; the worker emits `ActivityDeletedEvent` and skips the payload step).
- This is a long-retained **archive**. `sync_jobs` rows can be pruned aggressively after success; payloads are kept. Distinct lifecycle is the whole point of keeping them in separate tables.

### sync_state

One row per `(user_id, provider)`. Holds the **`SyncCursor`** watermark plus reconciliation bookkeeping. A small key-value-ish table, not a rich aggregate.

```sql
CREATE TABLE sync_state (
    id                      UUID        PRIMARY KEY,
    user_id                 UUID        NOT NULL,  -- id-ref to IAM users.id (no cross-BC FK)
    provider                TEXT        NOT NULL,
    last_synced_at          TIMESTAMPTZ NULL,      -- reconciliation lower bound (?after=)
    last_synced_activity_id BIGINT      NULL,      -- tie-break when start times collide
    last_reconciled_at      TIMESTAMPTZ NULL,      -- when reconciliation last ran for this source
    version                 BIGINT      NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Notes:
- The cursor lives **here, in Ingestion's schema** — deliberately not on IAM's `connected_sources`. Sync progress is an Ingestion concern; hanging it on an IAM table would be the cross-BC leak that `ApplicationModules.verify()` exists to catch.
- `last_synced_at` is nullable: a brand-new source has no watermark until the first backfill/reconciliation completes. Reconciliation treats a null cursor as "sync from the beginning" (or defers to backfill, which is already running from `IntegrationConnectedEvent`).
- `last_synced_activity_id` breaks ties when several activities share a `last_synced_at` second, so `?after=` advancement never skips or re-includes a boundary activity.
- `version` serializes concurrent cursor advancement (a webhook-driven update and a reconciliation update racing on the same row).

## Constraints and indexes

### webhook_events

```sql
-- Poller / processor picks up unprocessed deliveries.
CREATE INDEX ix_webhook_events_status
    ON webhook_events (status)
    WHERE status = 'RECEIVED';

-- Forensics: "what did Strava send for this activity / athlete?"
CREATE INDEX ix_webhook_events_object
    ON webhook_events (object_type, object_id);
```

Rationale:
- `ix_…_status` is **partial** on `status = 'RECEIVED'` — the processing query only ever wants the unprocessed tail, and the table grows monotonically (forensic archive), so a partial index stays small regardless of total volume.
- `ix_…_object` serves forensic lookups by Strava object; not on the hot path.
- Deliberately **no** unique index — duplicates are intended (see table notes).

### sync_jobs

```sql
-- THE idempotency constraint: one job per (provider, activity, aspect).
ALTER TABLE sync_jobs
    ADD CONSTRAINT uq_sync_jobs_idempotency
    UNIQUE (provider, provider_activity_id, aspect_type);

-- Worker claim sweep: find runnable jobs (PENDING, or FAILED past their backoff).
CREATE INDEX ix_sync_jobs_runnable
    ON sync_jobs (status, next_attempt_at);

-- Cancel-on-revoke and per-user operational queries.
CREATE INDEX ix_sync_jobs_user
    ON sync_jobs (user_id, status);
```

Rationale:
- `uq_sync_jobs_idempotency` is the single mechanism that collapses both duplicate sources (webhook redelivery; webhook+reconciliation overlap). A second `enqueue` for the same key is caught here, **before** the expensive `GET /activities/{id}`.
- `ix_…_runnable` serves the worker's claim query (`findByStatusAndNextAttemptAtBefore`). Including `next_attempt_at` lets a `FAILED` job become runnable again once its backoff elapses.
- `ix_…_user` serves `IntegrationRevokedEvent` handling (cancel this user's pending jobs) and operational/debug queries.

### raw_activity_payloads

```sql
-- One payload per activity; also the lookup for replay / "already have it?".
ALTER TABLE raw_activity_payloads
    ADD CONSTRAINT uq_raw_payloads_provider_activity
    UNIQUE (provider, provider_activity_id);

-- Per-user history scans (e.g. re-emit all of a user's payloads).
CREATE INDEX ix_raw_payloads_user
    ON raw_activity_payloads (user_id, provider);
```

Rationale:
- `uq_…_provider_activity` enforces "exactly once into the archive" and backs `findByProviderAndProviderActivityId` (the replay lookup, and a cheap "do we already have this?" guard before a fetch).
- `ix_…_user` supports bulk re-emission for one user without a full table scan.

### sync_state

```sql
-- One sync-state row per (user, provider).
ALTER TABLE sync_state
    ADD CONSTRAINT uq_sync_state_user_provider
    UNIQUE (user_id, provider);
```

Rationale:
- The unique constraint **is** the access pattern: `findByUserIdAndProvider` is the only lookup, and there is exactly one row per source. The reconciliation sweep reads the whole (small) table.

## Mapping notes (entity ↔ schema)

| Entity field | Column | Mapping detail |
|---|---|---|
| `WebhookEventEntity.rawBody: String` | `webhook_events.raw_body JSONB` | stored as JSON text |
| `WebhookEventEntity.status: WebhookStatus` | `status TEXT` | `@Enumerated(EnumType.STRING)` |
| `SyncJobEntity.status: SyncJobStatus` | `sync_jobs.status TEXT` | `@Enumerated(EnumType.STRING)` |
| `SyncJobEntity.source: SyncJobSource` | `sync_jobs.source TEXT` | `@Enumerated(EnumType.STRING)` |
| `SyncJobEntity.version: long` | `sync_jobs.version BIGINT` | `@Version` |
| `RawActivityPayloadEntity.payload: String` | `raw_activity_payloads.payload JSONB` | stored as JSON text |
| `SyncStateEntity.lastSyncedAt: Instant` | `sync_state.last_synced_at TIMESTAMPTZ` | the SyncCursor watermark |
| `*.userId: UUID` | `*.user_id UUID` | id-reference to IAM; **no** DB FK (cross-BC) |
| `createdAt/updatedAt` | `created_at/updated_at` | `@CreationTimestamp` / `@UpdateTimestamp` |

## Retention and growth

The four tables have very different growth and retention profiles — worth stating because it justifies keeping them separate:

| Table | Growth | Retention |
|---|---|---|
| `webhook_events` | one row per delivery (incl. duplicates) — highest churn | forensic archive; prune by age (e.g. keep 30–90 days) once processing is stable |
| `sync_jobs` | one row per (activity, aspect) of work | prunable aggressively once `COMPLETED`/`DEAD` and acknowledged downstream |
| `raw_activity_payloads` | one row per activity ever ingested | **long-lived**; this is the replay source of truth, kept indefinitely in MVP |
| `sync_state` | one row per connected source — effectively bounded by user count | permanent |

> Pruning policies (webhook_events / sync_jobs cleanup jobs) are an operational concern, not MVP-blocking. Noted here so the schema design reflects the intended lifecycles; concrete cleanup scheduling is deferred.

## Migrations

- Migrations use **Flyway**, as **per-BC changesets** run at application startup. Ingestion's migrations live under the Ingestion module, separate from IAM's.
- Suggested split (Flyway versioned naming):
  1. `V1__ingestion_create_webhook_events.sql`
  2. `V2__ingestion_create_sync_jobs.sql` — table + idempotency unique constraint + indexes
  3. `V3__ingestion_create_raw_activity_payloads.sql`
  4. `V4__ingestion_create_sync_state.sql`
- Adding a provider later (`GARMIN`) requires **no migration** — `provider` is `TEXT`, the change is in the application `Provider` enum.
- No DB-side crypto here. Unlike IAM's token columns, ingestion payloads are not encrypted at rest in MVP — they are activity data, not credentials. (If activity data is later deemed sensitive enough to encrypt, that is an additive change to the `payload` column handling, mirroring IAM's `EncryptedBytesConverter` approach.)

## What is intentionally NOT here

- **No cross-BC foreign keys.** `user_id` references IAM by value only — see schema ownership. This is what makes future extraction to a separate database mechanical.
- **No `webhook_events` → `sync_jobs` FK.** They correlate by `(provider, provider_activity_id)` and have independent lifecycles; a payload/job may also originate from backfill/reconciliation with no webhook row at all.
- **No rate-limit-budget table.** Budget tracking is Redis counters per token window (see [strava-rate-limits.md](../../technical-notes/strava-rate-limits.md) — *to be written*), not relational state — it is high-churn, ephemeral, and never needs to be transactional with activity data.
- **No normalized activity tables.** Normalization happens in Workout Catalog; Ingestion stores only the raw payload. The canonical `Workout` schema lives in Catalog's `database.md`.

## Next documents in this BC

- [events.md](events.md) — event payload schemas (`ActivityIngested`, `ActivityDeleted`, `IngestionFailed`)
- [api.md](api.md) — webhook receiver endpoint, internal sync triggers
- Sequence diagrams: `diagrams/sequence/activity-ingestion-webhook.md`, `initial-backfill.md`
- [domain-model.md](domain-model.md) — already written
