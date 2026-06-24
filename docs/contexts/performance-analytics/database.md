# Performance Analytics — Database Schema

Physical schema for the Performance Analytics BC: the TimescaleDB hypertable for tier-3 samples,
the relational tables for derived metrics, their constraints and indexes. The hypertable design
rationale (chunking, compression, continuous aggregates) is in
[timescaledb-schema.md](../../technical-notes/timescaledb-schema.md); this document is the concrete
DDL as it lives in the Analytics module. Conventions: snake_case, `timestamptz` UTC, Flyway per-BC.

## Schema ownership

Analytics owns its `analytics` schema. Other BCs never read these tables — they consume Analytics'
events or call its API. Cross-BC references are **plain columns without FKs**:

- `user_id` → IAM `users.id`
- `workout_id` → Catalog `workouts.id`
- `(provider, provider_activity_id)` → correlates to Catalog/Ingestion by value

The hypertable and the derived tables have **no foreign keys between each other either** — they
correlate by `(provider, provider_activity_id)` / `user_id`, keeping the time-series store
decoupled from the relational derived store (they have different lifecycles: samples may be
compressed/retention-dropped while derived metrics persist).

## The hypertable — activity_samples (tier-3)

```sql
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE activity_samples (
    user_id              UUID        NOT NULL,
    provider             TEXT        NOT NULL,
    provider_activity_id BIGINT      NOT NULL,
    t                    TIMESTAMPTZ NOT NULL,
    elapsed_sec          INT         NOT NULL,
    heartrate            SMALLINT    NULL,
    watts                INT         NULL,
    cadence              SMALLINT    NULL,
    velocity_ms          REAL        NULL,
    altitude_m           REAL        NULL
);

SELECT create_hypertable('activity_samples', 't', chunk_time_interval => INTERVAL '7 days');

-- Per-activity slice: "all samples for this activity, in order" (HR trace, lap slice, curve).
CREATE INDEX ix_samples_activity
    ON activity_samples (provider, provider_activity_id, elapsed_sec);

-- Per-user time-range scans (rare; most reads are per-activity or via continuous aggregates).
CREATE INDEX ix_samples_user_time
    ON activity_samples (user_id, t DESC);
```

Idempotent materialization is **delete-by-activity then bulk insert** (see
[timescaledb-schema.md](../../technical-notes/timescaledb-schema.md)); `ix_samples_activity` makes
that delete and the per-activity slice reads cheap. No row-level unique constraint in MVP — the
delete-then-insert scope guarantees no duplicates per activity.

### Continuous aggregate + policies

```sql
CREATE MATERIALIZED VIEW activity_rollup
WITH (timescaledb.continuous) AS
SELECT provider, provider_activity_id, user_id,
       time_bucket('1 hour', t) AS bucket,
       avg(heartrate)::int AS avg_hr, max(heartrate) AS max_hr,
       avg(watts) AS avg_watts,  max(watts)  AS max_watts
FROM activity_samples
GROUP BY provider, provider_activity_id, user_id, bucket;

ALTER TABLE activity_samples SET (timescaledb.compress,
    timescaledb.compress_segmentby = 'provider, provider_activity_id');
SELECT add_compression_policy('activity_samples', INTERVAL '14 days');
```

## Derived metrics — relational tables

These are the small, persisted **outputs** of computation — ordinary Postgres tables, plain JPA
entities, `@Version` for concurrent recompute.

### daily_training_load

```sql
CREATE TABLE daily_training_load (
    id             UUID    PRIMARY KEY,
    user_id        UUID    NOT NULL,        -- id-ref, no FK
    day            DATE    NOT NULL,
    training_load  DOUBLE PRECISION NOT NULL,  -- summed daily load (TSS-like)
    ctl            DOUBLE PRECISION NOT NULL,  -- fitness, EWMA ~42d
    atl            DOUBLE PRECISION NOT NULL,  -- fatigue, EWMA ~7d
    tsb            DOUBLE PRECISION NOT NULL,  -- form = ctl - atl
    version        BIGINT  NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One load row per user per day; also the upsert key for recompute-from-date.
ALTER TABLE daily_training_load
    ADD CONSTRAINT uq_dtl_user_day UNIQUE (user_id, day);

-- The fitness/form chart read: a user's series over a date range, in order.
CREATE INDEX ix_dtl_user_day ON daily_training_load (user_id, day);
```

`uq_dtl_user_day` is the heart of the recompute: when a `WorkoutUpdated/Deleted` changes a day's
load, Analytics recomputes that day and every later day, upserting each `(user_id, day)` row. The
unique key makes each recompute an idempotent upsert.

### personal_records

```sql
CREATE TABLE personal_records (
    id                   UUID    PRIMARY KEY,
    user_id              UUID    NOT NULL,    -- id-ref, no FK
    sport_type           TEXT    NOT NULL,
    record_type          TEXT    NOT NULL,    -- '5k', 'best_20min_power', 'longest_ride', ...
    value                DOUBLE PRECISION NOT NULL,  -- seconds | watts | metres, unit per record_type
    workout_id           UUID    NOT NULL,    -- id-ref to Catalog workout, no FK
    provider_activity_id BIGINT  NOT NULL,
    achieved_at          TIMESTAMPTZ NOT NULL,
    version              BIGINT  NOT NULL DEFAULT 0
);

-- One current record per (user, sport, record_type). Beating it updates the row.
ALTER TABLE personal_records
    ADD CONSTRAINT uq_pr_user_sport_type UNIQUE (user_id, sport_type, record_type);

CREATE INDEX ix_pr_user ON personal_records (user_id, sport_type);
```

`uq_pr_user_sport_type` keeps "the user's current 5k PR" a single row; a new best updates value +
`workout_id` + `achieved_at`. (A full PR *history* — every time a PR was beaten — would be an
additive append table later; MVP keeps current bests.)

### power/pace curve (derived, per activity)

```sql
CREATE TABLE activity_power_curve (
    id                   UUID    PRIMARY KEY,
    user_id              UUID    NOT NULL,
    provider_activity_id BIGINT  NOT NULL,
    duration_sec         INT     NOT NULL,    -- 1, 5, 15, 60, 300, 1200, 3600, ...
    best_watts           INT     NULL,
    best_velocity_ms     REAL    NULL
);

ALTER TABLE activity_power_curve
    ADD CONSTRAINT uq_apc_activity_duration UNIQUE (provider_activity_id, duration_sec);
CREATE INDEX ix_apc_user_duration ON activity_power_curve (user_id, duration_sec);
```

Computed once at materialization from the streams (a hypertable computation), stored relationally
because the read ("best 20-min power across my history") is a small indexed lookup, not a
time-series scan. `ix_apc_user_duration` serves the cross-activity curve aggregation.

## Migrations

Flyway, under the Analytics module:
1. `V1__analytics_enable_timescaledb.sql` — `CREATE EXTENSION`
2. `V2__analytics_create_activity_samples.sql` — hypertable + indexes
3. `V3__analytics_create_continuous_aggregates.sql` — rollup view + compression/retention policies
4. `V4__analytics_create_daily_training_load.sql`
5. `V5__analytics_create_personal_records.sql`
6. `V6__analytics_create_activity_power_curve.sql`

## What is intentionally NOT here

- **No canonical `Workout`** — that is Catalog. Analytics holds samples + derived facts only.
- **No raw payload storage** — Analytics reads Ingestion's archive by `rawPayloadId`; it does not
  re-store the verbatim JSON.
- **No cross-BC or cross-table foreign keys** — all references are id-values; even hypertable ↔
  derived-table is by value, since their lifecycles differ.
- **No GPS/polyline** — that tier-3-shaped datum lives in Catalog (whole-read for maps).

## Next documents in this BC

- [events.md](events.md) — consumed `WorkoutCreated/Updated/Deleted`; published `PersonalRecordSetEvent`
- [api.md](api.md) — stream slice, fitness/form chart, power curve, PR endpoints
- Sequence diagram: `diagrams/sequence/stream-materialization.md`
- [domain-model.md](domain-model.md), [timescaledb-schema.md](../../technical-notes/timescaledb-schema.md) — already written
