# Workout Catalog — API

The HTTP surface of the Workout Catalog BC. Catalog is **read-mostly** to the outside world:
workouts are created by consuming events (not by HTTP POST), so the API is dominated by reads —
a paged feed and a workout detail view. Errors follow the project-wide
[RFC 7807 conventions](../../conventions/api-errors.md) (`application/problem+json`). Auth is the
project JWT (`sub` = `user_id`); every endpoint scopes to the authenticated user and never trusts
a client-supplied user id.

## Design stance

- **No create/update/delete endpoints for workouts.** A workout's lifecycle is driven by Strava
  via Ingestion → events ([events.md](events.md)), not by client writes. There is no "POST a
  workout" in the MVP — activities come from the athlete's connected source, full stop.
- **The two reads map to the two fetch plans** from [database.md](database.md): the feed uses a
  flat **projection** (no aggregate hydration); the detail uses an **entity graph** (root + laps
  + efforts). The API shape is a direct consequence of the persistence design.

## `GET /api/workouts` — the feed

A paged, reverse-chronological list of the authenticated user's workouts. Backed by the
`WorkoutSummary` projection — it never hydrates the aggregate or its collections.

**Query parameters**

| Param | Type | Default | Notes |
|---|---|---|---|
| `sport` | `SportType` | — | optional filter; hits `ix_workouts_user_sport_started` |
| `from` / `to` | ISO date | — | optional `started_at` range |
| `page` | int | 0 | zero-based |
| `size` | int | 20 | capped (e.g. ≤100) |

**200 response** (summary projection — note the deliberate absence of laps/efforts/polyline):

```json
{
  "content": [
    {
      "id": "0f9c…",
      "sportType": "RIDE",
      "startedAt": "2026-06-21T06:12:00Z",
      "distanceM": 64230.0,
      "movingTimeSec": 8460,
      "avgHeartrate": 142
    }
  ],
  "page": 0, "size": 20, "totalElements": 540, "totalPages": 27
}
```

The feed is the hot path; keeping it a projection is what makes it cheap regardless of how many
laps each workout has. Pagination is mandatory — there is no unbounded "all workouts" response.

## `GET /api/workouts/{id}` — detail

The full aggregate for one workout: summary + laps + segment efforts + best efforts + the
encoded map polyline. Backed by the entity-graph fetch (`findWithDetailById`), which loads the
collections as a handful of batched selects rather than a Cartesian join (the multiple-bag
reasoning is in [database.md](database.md)).

**200 response** (abridged):

```json
{
  "id": "0f9c…",
  "sportType": "RIDE",
  "startedAt": "2026-06-21T06:12:00Z",
  "movingTimeSec": 8460,
  "elapsedTimeSec": 9120,
  "distanceM": 64230.0,
  "totalElevationGainM": 812.0,
  "avgHeartrate": 142,
  "maxHeartrate": 178,
  "avgWatts": 214.5,
  "mapPolyline": "ki{eF~ps…",
  "laps": [
    { "lapIndex": 0, "distanceM": 5000, "movingTimeSec": 642, "avgWatts": 230, "avgHeartrate": 138 }
  ],
  "segmentEfforts": [
    { "segmentId": 229781, "segmentName": "Col de …", "elapsedTimeSec": 1284, "distanceM": 7100, "prRank": 2 }
  ],
  "bestEfforts": [
    { "name": "5k", "elapsedTimeSec": 1180, "distanceM": 5000 }
  ]
}
```

**404** (RFC 7807) if the workout does not exist **or** is not owned by the caller — the two are
deliberately indistinguishable so the endpoint does not leak the existence of other users'
workouts:

```json
{ "type": "https://aperitivo.<env>/problems/workout-not-found",
  "title": "Workout not found", "status": 404, "detail": "No workout 0f9c… for this user." }
```

## What detail does NOT include — the tier boundary, surfaced in the API

The detail response carries the **encoded polyline** (tier-special, Catalog-owned) but **not**
per-second HR/power/cadence streams. Those are tier-3, owned by Performance Analytics. A client
that wants the HR trace for a workout (or for a specific lap, via the lap's `startIndex`/
`endIndex`) calls **Analytics'** stream endpoint, not Catalog. The split in the API mirrors the
split in storage:

- *"What was this workout and how was it structured?"* → Catalog (`GET /api/workouts/{id}`).
- *"What did my heart rate do, second by second?"* → Analytics (its streams/series endpoint).

This keeps Catalog's responses bounded and fast (no 40K-element arrays ever serialize out of
this BC) and routes heavy time-series to the BC built for it.

## Cross-BC reads — the published read port (not HTTP)

Analytics, when handling `WorkoutCreatedEvent`, needs the raw payload to extract streams. It does
**not** call Catalog's HTTP API for that — it reads Ingestion's archive via the in-process
**published read port** (`RawPayloadReader`), using `rawPayloadId` carried on the event. Catalog's
HTTP API is for the **client/UI**, not for inter-BC data flow; inter-BC data flow is events +
published ports ([ADR 0005](../../adr/0005-bounded-contexts.md)). Stated here so the API's scope
is unambiguous: this surface serves the app, not other BCs.

## Errors

| Status | When |
|---|---|
| `200 OK` | successful read |
| `400 Bad Request` | malformed query params (bad `sport`, page/size out of range) — RFC 7807 |
| `401 Unauthorized` | missing/invalid JWT |
| `404 Not Found` | workout absent or not owned by caller (indistinguishable, by design) |

All error bodies are `application/problem+json` per
[api-errors.md](../../conventions/api-errors.md).

## What is intentionally NOT in this API (MVP)

- **No workout write endpoints** (create/update/delete) — event-driven lifecycle only.
- **No stream endpoints** — tier-3 lives in Analytics' API.
- **No segment endpoints** — `Segment` is not a Catalog aggregate in MVP; efforts are read as
  part of their workout.
- **No bulk export** — a "download all my workouts" endpoint is a post-MVP GDPR/portability item.
- **No aggregate-stats endpoints** (totals, weekly mileage, PRs) — those are Analytics, not
  Catalog. Catalog answers "which workouts", Analytics answers "what do they add up to".

## Next documents in this BC

- Sequence diagram: `diagrams/sequence/activity-normalization.md`
- [domain-model.md](domain-model.md), [database.md](database.md), [events.md](events.md) — already written
