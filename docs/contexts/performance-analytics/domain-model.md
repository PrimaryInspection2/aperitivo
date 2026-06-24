# Performance Analytics — Domain Model

The domain model for the Performance Analytics BC: the per-second stream store (tier-3,
TimescaleDB), the derived training-load metrics (CTL/ATL/TSB), personal records, and trends.
This is where "what do the numbers mean over time?" lives — the complement to Catalog's
"what workouts happened?".

Style: layered ([ADR 0005](../../adr/0005-bounded-contexts.md)). Unlike Catalog, the modelling
weight here is **not** in JPA aggregates — it is in **time-series storage and computation**.
Analytics deliberately uses very little of the Hibernate association toolkit; its centre of
gravity is a TimescaleDB hypertable and the queries over it. Naming:
[conventions/naming.md](../../conventions/naming.md).

## Responsibility recap

Analytics owns the **tier-3 per-second telemetry** and everything **derived** from the body of a
training history:

1. **Materialize streams** — on `WorkoutCreatedEvent`/`WorkoutUpdatedEvent`, read the archived
   `RawActivityPayload` (by `rawPayloadId`), extract the numeric per-second series
   (heartrate, watts, cadence, velocity, altitude, time, distance) and store them in a
   **TimescaleDB hypertable**.
2. **Compute training load** — CTL (Chronic Training Load / fitness), ATL (Acute Training Load /
   fatigue), TSB (Training Stress Balance / form), per user over time.
3. **Detect personal records** — best efforts and power/pace curves across the whole history.
4. **Serve time-series and trend reads** — the HR trace for a workout, a power curve, a fitness
   chart over months.

Analytics does **not** own the canonical `Workout` (that is Catalog) and does **not** talk to
Strava (Ingestion already archived everything Analytics needs). It is a **pure consumer** of the
event stream plus the raw archive.

## Why tier-3 lives here, not in Catalog

The boundary was decided in Catalog's domain model and is restated from the consuming side:
per-second streams are 10³–10⁴ aligned samples per channel per activity, read as **whole series
for windowed/aggregate computation** — power curves, zone distributions, normalized power, the
inputs to CTL/ATL. That access pattern is columnar and time-partitioned, which is precisely what
a **TimescaleDB hypertable** is built for, and precisely what a JPA `@OneToMany` is catastrophic
at (the rejected anti-pattern in [Catalog domain-model.md](../workout-catalog/domain-model.md)).
So the streams land in the BC that actually computes over them, in storage designed for them.

The GPS polyline is the one tier-3-shaped datum that stays in **Catalog** (whole-read for map
rendering, already encoded by Strava). Analytics holds the **numeric** series only.

## Entities and stores — two very different shapes

Analytics has two storage shapes, and the split is the point:

### 1. The hypertable — `activity_samples` (TimescaleDB, not a JPA aggregate)

The per-second store. **Not** modelled as a rich JPA entity graph; it is a wide, append-mostly,
time-partitioned table written in bulk and queried with SQL/aggregates. Conceptually:

```
activity_samples (
    user_id, provider, provider_activity_id,
    t                 timestamptz,   -- sample time (hypertable partition key)
    elapsed_sec       int,           -- offset from activity start (lap start/end_index align here)
    heartrate         smallint,
    watts             int,
    cadence           smallint,
    velocity_ms       real,
    altitude_m        real
)
```

Written via **batch insert** (`COPY` / multi-row insert), thousands of rows per activity, never
through an ORM collection. Read via aggregate queries (`time_bucket`, `max(watts) over window`),
never hydrated into objects one row at a time. Detail in
[timescaledb-schema.md](../../technical-notes/timescaledb-schema.md).

> The lap's `start_index`/`end_index` from Catalog align to `elapsed_sec` here: that is how
> "HR trace for lap 3" is answered — Catalog gives the offsets, Analytics slices the samples.
> The two BCs correlate by `(provider, providerActivityId)` + offset, never by a DB join.

### 2. Derived metrics — small JPA entities

The *outputs* of computation are modest relational rows, and these **are** ordinary JPA entities
(but still no cross-aggregate associations — id-refs only):

```java
@Entity @Table(name = "daily_training_load")
public class DailyTrainingLoadEntity {
    @Id private UUID id;
    private UUID  userId;          // id-ref to IAM, no FK
    private LocalDate day;
    private double trainingLoad;   // daily TSS-like score
    private double ctl;            // chronic (fitness) — EWMA ~42d
    private double atl;            // acute (fatigue) — EWMA ~7d
    private double tsb;            // ctl - atl (form)
    @Version private long version;
}
```

```java
@Entity @Table(name = "personal_records")
public class PersonalRecordEntity {
    @Id private UUID id;
    private UUID   userId;
    private SportType sportType;
    private String recordType;         // "5k", "best_20min_power", "longest_ride", ...
    private double value;              // seconds, watts, metres — unit per recordType
    private UUID   workoutId;          // id-ref to the Catalog workout where it was set
    private long   providerActivityId;
    private Instant achievedAt;
    @Version private long version;
}
```

These are tiny (one row per user per day; a handful of PRs per user) — relational is exactly
right, and Hibernate here is plain CRUD, no associations. The contrast with the hypertable is
deliberate: **Analytics uses the right storage for each shape**, time-series for samples,
relational for the small derived facts.

## Computation model

### Training load → CTL / ATL / TSB

For each workout, Analytics computes a **training-load score** (a TSS-like value) from the
streams — using power where available (cycling), HR-based TRIMP otherwise (running/most sports),
falling back to duration×intensity when neither is present. Daily load is summed per user per day,
then:

- **CTL** = exponentially-weighted moving average of daily load over ~42 days (fitness).
- **ATL** = EWMA over ~7 days (fatigue).
- **TSB** = CTL − ATL (form; positive = fresh, negative = fatigued).

These are recomputed forward from any changed day — which is exactly why
`WorkoutUpdatedEvent` matters: an edited or corrected activity changes that day's load and every
CTL/ATL/TSB value from that date onward. A `WorkoutDeletedEvent` likewise triggers a
recompute-from-date. (This is the concrete reason Catalog splits create/update/delete into
distinct events — Analytics' reaction genuinely differs per case.)

### Personal records and curves

Best efforts (already in Catalog's tier-2) seed PR detection; the **power/pace curve** (best
average power/pace for every duration from 1s to hours) is computed from the **streams** — a
hypertable computation, not a Catalog read. A new workout updates the curve and may set PRs,
emitting `PersonalRecordSetEvent` for Notifications.

## Services

| Service | Responsibility |
|---|---|
| `StreamMaterializationService` | consumes `WorkoutCreated/Updated`, reads payload, bulk-inserts samples into the hypertable (idempotent per activity) |
| `TrainingLoadService` | computes per-workout load, recomputes CTL/ATL/TSB forward from the affected day |
| `PersonalRecordService` | updates power/pace curves and PRs from streams + best efforts; emits `PersonalRecordSetEvent` |
| `AnalyticsQueryService` | read side — stream slices, fitness/form charts, curves, PR lists |

## Persistence policy — Hibernate use in Analytics

The mirror image of Catalog:

| Feature | Used? | Where / why |
|---|---|---|
| TimescaleDB hypertable + `time_bucket`, continuous aggregates | ✅✅ | the whole tier-3 store — this is Analytics' centre of gravity |
| Bulk insert (`COPY`/batch) for samples | ✅ | thousands of rows/activity; never an ORM collection |
| Plain JPA entities (CRUD) for derived metrics | ✅ | `DailyTrainingLoad`, `PersonalRecord` — small relational rows |
| `@OneToMany` / entity graphs / `@BatchSize` | ❌ | no rich aggregates here; the heavy data is time-series, the light data is flat |
| `@Version` | ✅ | on derived-metric rows, to serialize concurrent recompute |
| Associations across aggregates/BCs | ❌ | `userId`, `workoutId`, `rawPayloadId` are id-refs |

This is the deliberate counterweight to Catalog: same project, opposite toolkit, because the data
shape is opposite. Showing both — and *why each fits its BC* — is the staff-level point.

## Invariants

1. **Idempotent materialization.** Samples for a given `(provider, providerActivityId)` are
   inserted once; re-delivery deletes-and-reinserts that activity's samples (or no-ops) — never
   duplicates. The hypertable carries a uniqueness/dedup strategy per activity (see schema note).
2. **Streams sourced once, from the archive.** Analytics reads `RawActivityPayload`, never Strava.
3. **CTL/ATL/TSB are a deterministic function of daily load.** Recompute-from-date reproduces
   them; a replay over the history yields identical curves.
4. **No canonical workout here.** Analytics holds samples + derived metrics, never the `Workout`
   aggregate (that is Catalog). Enforced by `ApplicationModules.verify()`.
5. **No JPA association crosses a BC boundary.** All cross-refs are id-values.

## Collaboration summary

```
WorkoutCreatedEvent / WorkoutUpdatedEvent (Catalog)
    → StreamMaterializationService  → reads RawActivityPayload (rawPayloadId) → hypertable bulk insert
    → TrainingLoadService           → recompute CTL/ATL/TSB forward from day
    → PersonalRecordService         → update curve/PRs → publishes PersonalRecordSetEvent
WorkoutDeletedEvent (Catalog)
    → delete activity's samples → recompute load forward from that day
AnalyticsQueryService → hypertable (stream slices, curves) + derived-metric tables (fitness chart, PRs)
```

## Next documents in this BC

- [database.md](database.md) — hypertable DDL, derived-metric tables, indexes, continuous aggregates
- [events.md](events.md) — consumed `WorkoutCreated/Updated/Deleted`; published `PersonalRecordSetEvent`
- [api.md](api.md) — stream-slice, fitness/form chart, power curve, PR endpoints
- Sequence diagram: `diagrams/sequence/stream-materialization.md`
- [timescaledb-schema.md](../../technical-notes/timescaledb-schema.md) — hypertable design deep-dive
