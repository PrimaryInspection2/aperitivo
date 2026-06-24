# Workout Catalog — Database Schema

Physical schema for the Workout Catalog BC: tables, constraints, indexes, and the Hibernate
fetch-plan specifics (entity graphs, `@BatchSize` behaviour, L2 cache evaluation) that the
[domain model](domain-model.md) calls "the playground". Conventions: snake_case, UUID PKs,
`timestamptz` (UTC), Flyway per-BC changesets.

## Schema ownership

Catalog owns its `catalog` schema. Other BCs never read these tables — they consume
`WorkoutCreated/Updated/Deleted` events. Two cross-BC references appear as **plain UUID/bigint
columns without DB foreign keys** (referential integrity across a BC boundary is the
application's job, per [ADR 0005](../../adr/0005-bounded-contexts.md)):

- `user_id` → IAM `users.id`
- `raw_payload_id` → Ingestion `raw_activity_payloads.id`

There is **one** real FK in this schema — `workout_id` on the child tables — because parent and
children are all Catalog-owned and form a single aggregate. That is the distinction the project
draws consistently: FKs *within* an aggregate/BC, id-refs *across* boundaries.

## Tables

### workouts (aggregate root)

```sql
CREATE TABLE workouts (
    id                       UUID        PRIMARY KEY,
    user_id                  UUID        NOT NULL,        -- id-ref to IAM, no FK
    provider                 TEXT        NOT NULL,        -- 'STRAVA'
    provider_activity_id     BIGINT      NOT NULL,
    raw_payload_id           UUID        NOT NULL,        -- id-ref to Ingestion, no FK
    sport_type               TEXT        NOT NULL,        -- SportType enum as string
    started_at               TIMESTAMPTZ NOT NULL,
    moving_time_sec          INT         NOT NULL,
    elapsed_time_sec         INT         NOT NULL,
    distance_m               DOUBLE PRECISION NOT NULL,
    total_elevation_gain_m   DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_heartrate            INT         NULL,
    max_heartrate            INT         NULL,
    avg_watts                DOUBLE PRECISION NULL,
    map_polyline             TEXT        NULL,            -- encoded polyline, whole-read
    version                  BIGINT      NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### laps

```sql
CREATE TABLE laps (
    id               UUID    PRIMARY KEY,
    workout_id       UUID    NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
    lap_index        INT     NOT NULL,            -- order within the workout
    distance_m       DOUBLE PRECISION NOT NULL,
    moving_time_sec  INT     NOT NULL,
    avg_watts        DOUBLE PRECISION NULL,
    avg_heartrate    INT     NULL,
    start_index      INT     NOT NULL,            -- offset into the (Analytics-owned) stream
    end_index        INT     NOT NULL
);
```

### segment_efforts

```sql
CREATE TABLE segment_efforts (
    id               UUID    PRIMARY KEY,
    workout_id       UUID    NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
    segment_id       BIGINT  NOT NULL,            -- id-ref to Strava's global segment, no FK
    segment_name     TEXT    NOT NULL,
    elapsed_time_sec INT     NOT NULL,
    distance_m       DOUBLE PRECISION NOT NULL,
    pr_rank          INT     NULL                 -- 1/2/3 if a PR on this segment, else null
);
```

### best_efforts

```sql
CREATE TABLE best_efforts (
    id               UUID    PRIMARY KEY,
    workout_id       UUID    NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
    name             TEXT    NOT NULL,            -- '400m', '1k', '5k', ...
    elapsed_time_sec INT     NOT NULL,
    distance_m       DOUBLE PRECISION NOT NULL
);
```

> `start_index` / `end_index` on a lap point into the per-second stream that lives in
> **Analytics**, not here — they are integer offsets, not a FK. A consumer that wants the HR
> trace for a lap reads the offsets from Catalog and the samples from Analytics. The two BCs
> correlate by `(provider, providerActivityId)` + index, never by a database join. This is the
> tier-2/tier-3 boundary made physical.

## Constraints and indexes

### workouts

```sql
-- One canonical workout per source activity. THE idempotency constraint.
ALTER TABLE workouts
    ADD CONSTRAINT uq_workouts_provider_activity
    UNIQUE (provider, provider_activity_id);

-- The hot read: a user's feed, newest first.
CREATE INDEX ix_workouts_user_started
    ON workouts (user_id, started_at DESC);

-- Filtered feed: a user's workouts of one sport, newest first.
CREATE INDEX ix_workouts_user_sport_started
    ON workouts (user_id, sport_type, started_at DESC);
```

Rationale:
- `uq_workouts_provider_activity` enforces invariant 1 and is the upsert key for
  re-normalization (an `update`-aspect re-ingest resolves the existing row by this key).
- `ix_…_user_started` serves the dominant query — paged reverse-chronological feed per user.
  The `DESC` matches the scan direction so no sort is needed.
- `ix_…_user_sport_started` serves the common "show me just my rides" filter without a separate
  sort.

### children

```sql
-- Fetch a workout's children in order; also the FK-side index Postgres does NOT auto-create.
CREATE INDEX ix_laps_workout            ON laps            (workout_id, lap_index);
CREATE INDEX ix_segment_efforts_workout ON segment_efforts (workout_id);
CREATE INDEX ix_best_efforts_workout    ON best_efforts    (workout_id);

-- Optional: cross-workout segment lookups ("all my efforts on segment X").
CREATE INDEX ix_segment_efforts_segment ON segment_efforts (segment_id);
```

The `workout_id` indexes matter specifically because Postgres **does not** auto-index the
referencing side of a FK. Without `ix_laps_workout` the `@BatchSize` `IN (...)` lap fetch — and
the `ON DELETE CASCADE` — would seq-scan. `ix_segment_efforts_segment` is speculative (only
needed if cross-workout segment history becomes a feature); kept here as a noted option, not a
commitment.

## Fetch plans — making the toolkit concrete

The domain model declares the mappings; here is how they behave at query time, which is the
part that demonstrates the judgement.

### Feed read — `@BatchSize` in action

The feed query selects a page of `workouts` (summary columns only — ideally a **projection**,
see below). Children are `LAZY`, so the feed pays nothing for laps/efforts it doesn't render.
If something *does* touch the collections across the page, `@BatchSize(50)` collapses what would
be N+1 selects into `ceil(N/50)` batched `IN (...)` selects. For a 30-item feed: **1** lap
query instead of 30.

### Detail read — entity graph, not EAGER

A single-workout detail view wants the root **and** its laps/efforts in as few queries as
possible. We do **not** make the associations EAGER (that would tax every read including the
feed). Instead, an **entity graph** opts in per-query:

```java
@EntityGraph(attributePaths = {"laps", "segmentEfforts", "bestEfforts"})
Optional<WorkoutEntity> findWithDetailById(UUID id);
```

This issues a fetch plan that loads the graph for *this* query only. Note the multiple-bag
caveat: fetching **three** `List` collections in one SQL join would trigger Hibernate's
`MultipleBagFetchException` (Cartesian product across bags). Two clean ways out, both documented
as the intended approach:
- Map the collections as `Set` (or `List` with `@OrderColumn`) so they are not plain bags, **or**
- Let the entity graph fetch them as **separate** batched selects (Hibernate issues one select
  per collection, not one Cartesian join) — which is the default and is fine at tier-2
  cardinality.

We take the second: the entity graph loads laps/efforts/best-efforts as a handful of batched
selects keyed on `workout_id`. Three extra indexed selects for a detail view is correct and
cheap; forcing a single Cartesian join is the premature optimization that bites.

### Feed projections — don't hydrate the aggregate to list it

The feed never needs the aggregate; it needs a flat row of summary fields. So the read side uses
a **projection** (interface- or record-based) rather than the entity:

```java
public record WorkoutSummary(
    UUID id, SportType sportType, Instant startedAt,
    double distanceM, int movingTimeSec, Integer avgHeartrate) {}
```

```java
@Query("""
    select new ...WorkoutSummary(w.id, w.sportType, w.startedAt,
                                 w.distanceM, w.movingTimeSec, w.avgHeartrate)
    from WorkoutEntity w
    where w.userId = :userId
    order by w.startedAt desc
""")
Page<WorkoutSummary> findFeed(UUID userId, Pageable pageable);
```

This sidesteps the entire association machinery for the common case: no lazy proxies, no
`@BatchSize`, no L2 interaction — just the columns the feed shows. The aggregate (with its
toolkit) is for the detail path; the projection is for the list path. Knowing which to use where
is the point.

## L2 cache — evaluated, not reflexively enabled

Second-level cache is a *candidate* for `workouts` (read-mostly once normalized, re-read across
sessions), but it is **not auto-on**. The evaluation:

- **For:** a workout is immutable in practice after normalization (updates are rare Strava
  edits); detail reads repeat; the aggregate is small (tier-3 is excluded, so no 40K-sample
  poisoning — *this is the payoff of the boundary*).
- **Against:** the feed uses projections (which bypass L2 entity cache anyway), the working set
  is large (all users' history), and cache invalidation on the occasional `update`-aspect
  re-normalization adds complexity.

Decision: **defer L2 to a measured need.** If detail-read latency or DB load justifies it,
enable read-write L2 on `WorkoutEntity` + collections with a region per entity, invalidated on
re-normalization via the cache's natural entity eviction. Documented as a ready lever, not an
MVP default. (Contrast: enabling L2 here is *safe to consider* only because streams are out —
the same cache on a stream-bearing aggregate would be indefensible.)

## Migrations

Flyway per-BC, under the Catalog module:
1. `V1__catalog_create_workouts.sql` — root + unique + feed indexes
2. `V2__catalog_create_laps.sql`
3. `V3__catalog_create_segment_efforts.sql`
4. `V4__catalog_create_best_efforts.sql`

Adding a `SportType` needs no migration (`TEXT`). Adding tier-2 fields is additive.

## What is intentionally NOT here

- **No per-second sample / stream tables.** Tier 3 lives in Analytics' TimescaleDB. The absence
  is the architecture.
- **No `segments` table.** `segment_id` is an id-ref; `Segment` is not a Catalog aggregate in MVP.
- **No cross-BC foreign keys.** `user_id`, `raw_payload_id`, `segment_id` are plain columns.
- **No computed-metric columns** (CTL/ATL/TSB, normalized power, PRs-over-time). Those are
  Analytics outputs, not Catalog structure.

## Next documents in this BC

- [events.md](events.md) — `WorkoutCreated/Updated/Deleted`; consumed `ActivityIngested/Deleted`
- [api.md](api.md) — feed (projection) and detail (entity-graph) endpoints
- Sequence diagram: `diagrams/sequence/activity-normalization.md`
- [domain-model.md](domain-model.md) — already written
