# Performance Analytics — API

The HTTP surface of the Performance Analytics BC. Like Catalog, Analytics is **read-only** to
clients — all writes are event-driven. Its reads are the heavy time-series and trend queries that
Catalog deliberately does *not* serve: per-second stream slices, fitness/form charts, power curves,
PR lists. Errors follow [RFC 7807](../../conventions/api-errors.md); auth is the project JWT
(`sub` = `user_id`), every endpoint scoped to the caller.

## Why these endpoints live here and not in Catalog

The API boundary mirrors the storage boundary. Catalog answers *"which workouts, and how were they
structured?"*; Analytics answers *"what did the numbers do, over a workout and over a season?"*.
Concretely, anything backed by the **hypertable** (per-second samples) or by **derived metrics**
(CTL/ATL/TSB, curves, PRs) is here. A client assembling a workout-detail page calls **both**:
Catalog for summary+laps+map, Analytics for the HR/power trace.

## `GET /api/activities/{providerActivityId}/streams` — per-second trace

The tier-3 slice for one activity: aligned per-second series for the requested channels. Backed by
a per-activity hypertable scan (`ix_samples_activity`).

**Query parameters**

| Param | Type | Default | Notes |
|---|---|---|---|
| `channels` | csv | all | `heartrate,watts,cadence,velocity,altitude` |
| `from` / `to` | int (elapsed_sec) | full | optional window — e.g. a single lap via its `startIndex`/`endIndex` |
| `resolution` | enum | `high` | `high` (raw) or `low` (decimated for long activities) |

**200 response** (parallel arrays, index-aligned — the natural shape for charting):

```json
{
  "providerActivityId": 14882012345,
  "elapsedSec": [0, 1, 2, 3, "…"],
  "heartrate":  [98, 101, 104, 107, "…"],
  "watts":      [0, 120, 180, 210, "…"]
}
```

Lap-scoped trace is just this endpoint with `from`/`to` set to the lap's offsets (which the client
got from Catalog's workout detail) — the two BCs meet at `(providerActivityId, elapsed_sec)`, never
at a database join. For long activities, `resolution=low` returns a decimated series so a months-out
zoom doesn't ship 40K points (full resolution for a single-activity view).

## `GET /api/athlete/fitness` — CTL / ATL / TSB chart

The fitness/form trend: the user's daily CTL, ATL, TSB over a date range. Backed by
`daily_training_load` (`ix_dtl_user_day`) — a small indexed relational read, not a hypertable scan.

**Query**: `from`, `to` (ISO dates; default last 90 days).

**200**:

```json
{
  "from": "2026-03-26", "to": "2026-06-24",
  "series": [
    { "day": "2026-06-22", "ctl": 78.4, "atl": 64.1, "tsb": 14.3, "trainingLoad": 0 },
    { "day": "2026-06-23", "ctl": 79.0, "atl": 92.5, "tsb": -13.5, "trainingLoad": 210 }
  ]
}
```

This is the headline "fitness over time" graph — fitness (CTL) rising, fatigue (ATL) spiking after
hard days, form (TSB) dipping negative under load and going positive on taper.

## `GET /api/athlete/power-curve` — best-effort curve

The power (or pace) curve: best average watts (cycling) or velocity (running) for each duration,
aggregated across the history. Backed by `activity_power_curve` (`ix_apc_user_duration`).

**Query**: `sport` (required), `from`/`to` (optional window — "my curve this season vs all-time").

**200**:

```json
{
  "sportType": "RIDE",
  "points": [
    { "durationSec": 5,    "bestWatts": 1180 },
    { "durationSec": 60,   "bestWatts": 640  },
    { "durationSec": 300,  "bestWatts": 412  },
    { "durationSec": 1200, "bestWatts": 318  }
  ]
}
```

## `GET /api/athlete/records` — personal records

Current PRs for the user, optionally by sport. Backed by `personal_records` (`ix_pr_user`).

**200**:

```json
{
  "records": [
    { "sportType": "RUN",  "recordType": "5k", "value": 1421, "achievedAt": "2026-06-10T07:00:00Z", "workoutId": "…" },
    { "sportType": "RIDE", "recordType": "best_20min_power", "value": 318, "achievedAt": "2026-05-02T16:20:00Z", "workoutId": "…" }
  ]
}
```

`value` units depend on `recordType` (seconds for distance PRs, watts for power, metres for
longest) — documented per record type. `workoutId` links back to Catalog for the source workout.

## Errors

| Status | When |
|---|---|
| `200 OK` | successful read |
| `400 Bad Request` | bad params (unknown channel/sport, inverted date range) — RFC 7807 |
| `401 Unauthorized` | missing/invalid JWT |
| `404 Not Found` | no samples/metrics for the requested activity under this user (indistinguishable from not-owned, by design) |

## What is intentionally NOT in this API (MVP)

- **No write endpoints** — all state is event-derived.
- **No canonical workout fields** (summary, laps, map) — those are Catalog's API; Analytics does
  not duplicate them.
- **No raw-payload endpoint** — the archive is Ingestion's; Analytics reads it in-process, not over
  HTTP, and does not re-expose it.
- **No cross-user / leaderboard endpoints** — segment leaderboards and social comparison are
  post-MVP (and would pull `Segment` into a real aggregate first).
- **No CSV/bulk export** — portability export is a post-MVP GDPR item.

## Next documents in this BC

- Sequence diagram: `diagrams/sequence/stream-materialization.md`
- [domain-model.md](domain-model.md), [database.md](database.md), [events.md](events.md) — already written
- [timescaledb-schema.md](../../technical-notes/timescaledb-schema.md) — hypertable deep-dive
