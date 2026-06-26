# Strava Integration: Data Model Mapping

How Strava's activity JSON maps to Aperitivo's canonical model — field by field, tier by tier. This
is the **translation reference** for the anti-corruption layer: it documents what Strava sends and
where each piece lands. The *normalization logic* is Catalog's
([ActivityNormalizationService](../../contexts/workout-catalog/domain-model.md)); the *tier split*
is the load-bearing decision below.

## The three tiers (recap)

A Strava detailed activity decomposes into three tiers of very different scale, and they land in
**different Bounded Contexts**:

| Tier | Strava source | Lands in | As |
|---|---|---|---|
| 1 — summary | detailed activity top-level fields | Catalog | `Workout` root columns |
| 2 — structured collections | `laps[]`, `segment_efforts[]`, `best_efforts[]` | Catalog | `@OneToMany` children |
| 3 — per-second streams | `GET /activities/{id}/streams` | **Analytics** | TimescaleDB `activity_samples` |

The GPS route is a special case: Strava provides an **encoded polyline** (`map.summary_polyline` /
`map.polyline`) which Catalog stores as a string (`mapPolyline`), whole-read for map rendering — it
does **not** go to the numeric stream store. This whole split is the core engineering decision
documented in [Catalog domain-model](../../contexts/workout-catalog/domain-model.md); this note is
the field-level view of it.

## Tier 1 — summary → `Workout` root

| Strava field (`GET /activities/{id}`) | Workout field | Notes |
|---|---|---|
| `id` | `providerActivityId` | the Strava activity id (`long`) |
| `external_id` | — | not stored |
| `sport_type` (preferred) / `type` (legacy) | `sportType` | mapped to our `SportType` enum; unknown → `OTHER` |
| `start_date` | (used for `startedAt`, UTC) | absolute UTC |
| `start_date_local` | (used by Planning matching) | athlete-local wall clock |
| `elapsed_time` | `elapsedTimeSec` | seconds |
| `moving_time` | `movingTimeSec` | seconds |
| `distance` | `distanceM` | metres |
| `total_elevation_gain` | `totalElevationGainM` | metres |
| `average_heartrate` | `avgHeartrate` | nullable — not all activities have HR |
| `max_heartrate` | `maxHeartrate` | nullable |
| `average_watts` | `avgWatts` | nullable — cycling power, often absent |
| `map.summary_polyline` / `map.polyline` | `mapPolyline` | encoded polyline string, nullable |

### `sport_type` vs `type` — the legacy quirk

Strava has **two** activity-kind fields:
- `type` — the **legacy** enum (`Run`, `Ride`, `Swim`, …), broader-grained.
- `sport_type` — the **newer**, finer enum (`TrailRun`, `GravelRide`, `MountainBikeRide`, …),
  introduced later and the one Strava recommends.

We map from **`sport_type`** (falling back to `type` only if `sport_type` is absent on an old
activity), onto our `SportType`. Strava values we don't model map to `OTHER` rather than failing
normalization — the same defensive posture Ingestion takes with the raw payload.

## Tier 2 — structured collections → aggregate children

### `laps[]` → `Lap`

| Strava `laps[i]` | Lap field |
|---|---|
| `lap_index` (or array order) | `lapIndex` |
| `distance` | `distanceM` |
| `moving_time` | `movingTimeSec` |
| `average_watts` | `avgWatts` (nullable) |
| `average_heartrate` | `avgHeartrate` (nullable) |
| `start_index` / `end_index` | `startIndex` / `endIndex` |

`start_index`/`end_index` are **offsets into the activity's stream arrays** — they index tier-3 data
that lives in Analytics. This is the physical link between a Catalog lap and Analytics samples:
correlate by `(providerActivityId, index)`, never a DB join
([Catalog database](../../contexts/workout-catalog/database.md)).

### `segment_efforts[]` → `SegmentEffort`

| Strava `segment_efforts[i]` | SegmentEffort field |
|---|---|
| `segment.id` | `segmentId` (plain id-ref, **not** a modeled `Segment`) |
| `segment.name` | `segmentName` |
| `elapsed_time` | `elapsedTimeSec` |
| `distance` | `distanceM` |
| `pr_rank` | `prRank` (nullable; 1/2/3 if a PR) |

`segment.id` is stored as a bare `long` — we do **not** model Strava's global `Segment` as an
aggregate in MVP ([Catalog domain-model](../../contexts/workout-catalog/domain-model.md)). The
detailed activity must be fetched **with efforts** (`include_all_efforts=true` if needed) to get the
full list.

### `best_efforts[]` → `BestEffort`

| Strava `best_efforts[i]` | BestEffort field |
|---|---|
| `name` | `name` (e.g. "5k", "1 mile") |
| `elapsed_time` | `elapsedTimeSec` |
| `distance` | `distanceM` |

Strava computes these for runs (fastest 400m, 1k, 5k, …); they seed Analytics' PR detection.

## Tier 3 — streams → Analytics hypertable

Fetched separately: `GET /activities/{id}/streams?keys=heartrate,watts,cadence,velocity_smooth,altitude,time,latlng&key_by_type=true`.

| Strava stream key | Analytics `activity_samples` column | Notes |
|---|---|---|
| `time` | (drives `elapsed_sec` / `t`) | seconds from start; the index axis |
| `heartrate` | `heartrate` | `SMALLINT` |
| `watts` | `watts` | `INT`; nullable (power meter only) |
| `cadence` | `cadence` | `SMALLINT` |
| `velocity_smooth` | `velocity_ms` | `REAL` (m/s) |
| `altitude` | `altitude_m` | `REAL` |
| `latlng` | — (→ Catalog polyline, not numeric store) | GPS handled as polyline in Catalog |

Stream arrays are **index-aligned and parallel** — `heartrate[i]`, `watts[i]`, `time[i]` describe
the same instant. Strava returns them with `key_by_type=true` as a keyed object. They are
bulk-inserted into the hypertable, **never** an ORM collection
([timescaledb-schema.md](../../technical-notes/timescaledb-schema.md)). `latlng` is the exception
routed to Catalog's polyline, not Analytics' numeric columns.

### Stream quirks

- **Streams may be absent or partial** — an indoor activity has no `latlng`; a non-power activity
  has no `watts`; a manually-entered activity may have **no streams at all**. Nullable columns and
  optional channels handle this.
- **Resolution** can be requested (`low`/`medium`/`high`) — we take high for materialization and may
  decimate on read ([Analytics api](../../contexts/performance-analytics/api.md)).
- **`time` is not necessarily 1 Hz** — it's seconds-from-start but may skip (auto-pause, GPS gaps).
  The `time[]` array is the authoritative index, not the array position.

## Replay-ability of the mapping

Because the verbatim `RawActivityPayload` is archived in Ingestion, the entire mapping above is a
**pure function of stored data** — a normalization bug can be fixed and **replayed** over the archive
without re-calling Strava ([Catalog domain-model](../../contexts/workout-catalog/domain-model.md),
[idempotency-and-outbox.md](../../technical-notes/idempotency-and-outbox.md)). This is why the
mapping is documented as a translation table, not embedded logic: it can be re-derived.

## What we deliberately drop

Strava sends much more than we keep. Explicitly **not** mapped in MVP:
- `kudos_count`, `comment_count`, `athlete_count`, `photo_count` — social signals, out of scope
  ([ADR 0006](../../adr/0006-no-social-challenges.md)).
- `gear_id` / equipment — not modeled in MVP.
- `device_name`, `embed_token`, `upload_id` — Strava-internal plumbing.
- `splits_metric` / `splits_standard` — we use `laps` as the canonical sub-segment; Strava's
  auto-splits are redundant with our tier-2 laps for MVP purposes.
- `calories`, `suffer_score` (relative effort) — candidate future summary fields; not in MVP root.

Dropping these keeps the canonical model focused on training structure, not the social/equipment
metadata Strava layers on.

## Related

- [Catalog domain-model](../../contexts/workout-catalog/domain-model.md) — the aggregate this maps into, and the tier-boundary rationale
- [Catalog database](../../contexts/workout-catalog/database.md) — the physical columns
- [Analytics domain-model](../../contexts/performance-analytics/domain-model.md) — tier-3 stream ownership
- [timescaledb-schema.md](../../technical-notes/timescaledb-schema.md) — the hypertable streams land in
- [api-quirks.md](api-quirks.md) — the summary-vs-detail and streams-endpoint quirks behind this
