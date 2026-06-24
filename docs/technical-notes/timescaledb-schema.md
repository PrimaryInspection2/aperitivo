# Technical Note: TimescaleDB Schema

How Performance Analytics stores and queries tier-3 per-second telemetry. Complements the
[Performance Analytics BC](../contexts/performance-analytics/domain-model.md) (which references
this note for hypertable mechanics) and [database.md](../contexts/performance-analytics/database.md)
(the concrete DDL). This note is the *why and how* of choosing TimescaleDB and shaping the
hypertable.

## Why TimescaleDB at all

Per-second streams are the one dataset in Aperitivo whose shape defeats ordinary relational
modelling: 10³–10⁴ aligned samples per channel per activity, append-mostly, queried as **windowed
aggregates over time** (power curves, zone distributions, fitness trends) rather than as
individual rows. The Catalog domain model spells out why this must **not** be a JPA `@OneToMany`.
The positive choice is a **time-series store**, and TimescaleDB is the one that:

- is **Postgres** — same instance, same SQL, same transactions, same Flyway, same backups, no new
  database technology in the stack (decisive for a single-VPS deployment);
- gives **automatic time-partitioning** (hypertable chunks) so inserts and time-range scans stay
  fast as the table grows to hundreds of millions of rows;
- provides **continuous aggregates** (incrementally-maintained materialized views) for the
  roll-ups Analytics reads constantly;
- provides **native compression** of old chunks (10×+ on this kind of data).

It is an **extension**, not a separate server — which is exactly why it fits a project that
deliberately avoided adding Kafka, Keycloak, and a separate time-series DB. One Postgres,
extended.

## The hypertable

```sql
CREATE TABLE activity_samples (
    user_id              UUID        NOT NULL,
    provider             TEXT        NOT NULL,
    provider_activity_id BIGINT      NOT NULL,
    t                    TIMESTAMPTZ NOT NULL,   -- absolute sample time
    elapsed_sec          INT         NOT NULL,   -- offset from activity start
    heartrate            SMALLINT    NULL,
    watts                INT         NULL,
    cadence              SMALLINT    NULL,
    velocity_ms          REAL        NULL,
    altitude_m           REAL        NULL
);

SELECT create_hypertable('activity_samples', 't', chunk_time_interval => INTERVAL '7 days');
```

Design choices:

- **Partition on `t` (sample time).** Training data is queried by time window (this week's
  samples, this activity's window, this month's load). 7-day chunks keep each chunk a manageable
  size and align with the weekly cadence of the fitness queries.
- **Nullable metric columns.** Not every activity has power (only cycling with a meter) or HR.
  Null beats a separate per-channel table — the channels are aligned by `(activity, elapsed_sec)`
  and almost always queried together, so a wide row is the right shape (the opposite of the
  tier-2 normalization, where separate small tables were right).
- **`elapsed_sec` alongside `t`.** Lap `start_index`/`end_index` from Catalog are offsets; matching
  them to `elapsed_sec` is how "the HR trace for lap 3" is sliced without Catalog and Analytics
  sharing a key beyond `(providerActivityId, offset)`.
- **`SMALLINT`/`REAL` widths.** HR and cadence fit `SMALLINT`; velocity/altitude as `REAL` halve
  the bytes vs `DOUBLE` at precision that is meaningless to exceed for sensor data. At 10⁴ rows ×
  millions of activities, column width is real storage.

### Identity / dedup per activity

There is no per-row natural key worth enforcing (a sample is `(activity, elapsed_sec)`), but
**materialization must be idempotent per activity** (re-delivered `WorkoutCreated`, or a
`WorkoutUpdated` re-materialization). The strategy is **delete-then-bulk-insert scoped to the
activity**, inside one transaction:

```sql
DELETE FROM activity_samples
 WHERE provider = ? AND provider_activity_id = ?;
-- then COPY / multi-row INSERT the fresh samples
```

This is simpler and faster than per-row upsert for bulk time-series, and it makes an `update`
re-materialization trivially correct. A partial unique index on `(provider, provider_activity_id,
elapsed_sec)` is available if stricter row-level idempotency is ever wanted, but delete-by-activity
is the MVP approach (cheaper bulk path).

## Continuous aggregates — the roll-ups Analytics reads

The fitness/form charts and zone summaries do not scan raw samples each time; they read
**continuous aggregates** that TimescaleDB maintains incrementally:

```sql
-- Per-activity rollup: feeds training-load and curve computation.
CREATE MATERIALIZED VIEW activity_rollup
WITH (timescaledb.continuous) AS
SELECT provider, provider_activity_id, user_id,
       time_bucket('1 hour', t)         AS bucket,
       avg(heartrate)::int              AS avg_hr,
       max(heartrate)                   AS max_hr,
       avg(watts)                       AS avg_watts,
       max(watts)                       AS max_watts
FROM activity_samples
GROUP BY provider, provider_activity_id, user_id, bucket;
```

Continuous aggregates refresh on a policy (e.g. lag of a few minutes), so reads hit a small
maintained view rather than the raw hypertable. The power/pace **curve** (best average power over
every duration) is a heavier computation run at materialization time and stored in a derived table,
not as a continuous aggregate — it is a per-activity one-shot, not a rolling window.

## Retention and compression

Two policies, both native:

```sql
-- Compress chunks older than 14 days (old samples are read-rarely, never written).
ALTER TABLE activity_samples SET (timescaledb.compress,
    timescaledb.compress_segmentby = 'provider, provider_activity_id');
SELECT add_compression_policy('activity_samples', INTERVAL '14 days');
```

- **Compression** after 14 days: recent activities (the ones being viewed and folded into ATL)
  stay uncompressed and fast; the long tail compresses 10×+. `segmentby` on the activity keeps
  per-activity slice reads efficient even when compressed.
- **Retention:** raw samples are, in principle, reconstructable from `RawActivityPayload`
  (Ingestion's archive), so a retention policy *dropping* very old raw samples is **possible**
  without data loss — the derived metrics (CTL/ATL/PRs) are already persisted relationally. MVP
  keeps raw samples indefinitely (compressed); a drop-after-N-years policy is a documented lever,
  not an MVP default. This is the payoff of the archive-as-source-of-truth design: the heavy store
  is a *cache* of a verbatim original.

## What stays relational (not in the hypertable)

The **derived** outputs are small and belong in ordinary Postgres tables (plain JPA entities):
`daily_training_load` (one row/user/day: CTL/ATL/TSB) and `personal_records` (a handful/user).
They are queried by user+date range, not by time-bucket over millions of rows, so they are
relational, `@Version`-guarded for concurrent recompute, and need no hypertable. The split —
**samples in a hypertable, derived facts in relational tables** — is the core schema decision of
the BC.

## Migrations

Flyway, under the Analytics module. The `CREATE EXTENSION timescaledb` and `create_hypertable`
calls live in the first Analytics migration; continuous aggregates and policies follow. Because
TimescaleDB is a Postgres extension, this is ordinary Flyway SQL — no separate migration tool.

## Observability

- Chunk count and size, compression ratio, continuous-aggregate refresh lag.
- Materialization batch size and duration per activity; recompute-from-date span on updates.
- Alert: continuous-aggregate refresh falling behind, or materialization latency growing
  (backlog of `WorkoutCreated` events).

## Future steps (not MVP)

- **Drop-old-raw-samples retention** once the archive-replay path is exercised in production.
- **Per-channel downsampling** for very long activities in chart reads (read a decimated series
  for a months-long zoom-out, full resolution for a single activity).
- **Separate read replica / tablespace** for the hypertable if analytical load competes with OLTP.

## Related

- [Performance Analytics — domain model](../contexts/performance-analytics/domain-model.md)
- [Performance Analytics — database](../contexts/performance-analytics/database.md)
- [Workout Catalog — domain model](../contexts/workout-catalog/domain-model.md) (the tier boundary)
- [ADR 0005: Bounded contexts](../adr/0005-bounded-contexts.md)
