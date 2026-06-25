# Training Planning — API

The HTTP surface of the Training Planning BC: plan authoring (CRUD), the calendar/detail read, the
manual match override, and compliance reads. Errors follow
[RFC 7807](../../conventions/api-errors.md); auth is the project JWT (`sub` = `user_id`), every
endpoint scoped to the caller. Aggregate and matching design: [domain-model.md](domain-model.md).

## Design stance

Planning is the one downstream-of-Catalog BC with a **substantial write surface** — because plans
are **user-authored** (unlike workouts, which are event-derived). So unlike Catalog/Analytics
(read-only to clients), Planning exposes plan CRUD. What it does **not** expose is any way to create
a match or a session-completion directly — those are produced by the matching engine consuming
`WorkoutCreated`. The only match-related write a client can make is a **manual override**.

## Plan authoring

### `POST /api/plans` — create a plan

```json
{
  "name": "8-week base build",
  "startDate": "2026-07-01",
  "endDate": "2026-08-26",
  "sessions": [
    {
      "sessionDate": "2026-07-01",
      "localTime": "06:30",
      "sportType": "RUN",
      "targets": [
        { "type": "DURATION", "value": 3600, "unit": "sec" },
        { "type": "DISTANCE", "value": 10000, "unit": "m" },
        { "type": "TSS", "value": 65, "unit": "tss" }
      ]
    }
  ]
}
```

Creates the `Plan` aggregate (plan + sessions + targets) in one transaction. Returns `201` with the
created plan (detail shape below). A plan starts `DRAFT`; `PUT .../activate` moves it to `ACTIVE`
(only `ACTIVE` plans' sessions participate in matching and reminders). The planned `TSS` target is
stored as Planning-owned data — it is shown to the user but not evaluated against Analytics in MVP
(see [domain-model.md](domain-model.md)).

### `GET /api/plans` — list (projection)

A user's plans, newest first, as a flat `PlanSummary` projection (no session hydration):

```json
{
  "content": [
    { "id": "p1…", "name": "8-week base build", "startDate": "2026-07-01", "endDate": "2026-08-26", "status": "ACTIVE", "sessionCount": 32 }
  ],
  "page": 0, "size": 20, "totalElements": 4, "totalPages": 1
}
```

### `GET /api/plans/{id}` — detail / calendar (entity graph)

The full aggregate: plan + scheduled sessions + each session's targets + match state. Backed by the
entity-graph fetch (separate batched selects for the two collection levels, not a Cartesian join).

```json
{
  "id": "p1…", "name": "8-week base build", "status": "ACTIVE",
  "startDate": "2026-07-01", "endDate": "2026-08-26",
  "sessions": [
    {
      "id": "s1…", "sessionDate": "2026-07-01", "localTime": "06:30",
      "sportType": "RUN", "status": "COMPLETED",
      "matchedWorkoutId": "w9…", "manualMatch": false, "complianceScore": 0.92,
      "targets": [
        { "type": "DISTANCE", "value": 10000, "unit": "m" },
        { "type": "TSS", "value": 65, "unit": "tss" }
      ],
      "compliance": [
        { "targetType": "DISTANCE", "planned": 10000, "actual": 9400, "deltaPct": -0.06 },
        { "targetType": "DURATION", "planned": 3600, "actual": 3520, "deltaPct": -0.022 }
      ]
    }
  ]
}
```

The `compliance` array carries per-target deltas for a matched session; note **no `TSS` row** in
MVP compliance (actual TSS is not read). `matchedWorkoutId` links to Catalog for the source workout.

### `PUT /api/plans/{id}` / `DELETE /api/plans/{id}`

Update plan metadata/sessions/targets (full-aggregate replace or patch), and delete a plan
(cascades to sessions/targets/compliance). `409` on an optimistic-lock conflict (the plan was
modified concurrently — e.g. a match landed mid-edit), with the RFC 7807 body indicating a retry.

## Matching

### `POST /api/plans/{planId}/sessions/{sessionId}/match` — manual override

Explicitly link a workout to a session, overriding the algorithm.

```json
{ "workoutId": "w42…" }
```

Sets the match as **manual** (sticky — never overwritten by later auto-matching), recomputes
compliance for the pair, transitions the session to `COMPLETED`, and emits `SessionCompleted`
(`manualMatch: true`). `409` if that workout is already matched to another session (the client must
unmatch first). `404` if the session/workout isn't the caller's.

### `DELETE /api/plans/{planId}/sessions/{sessionId}/match` — clear a match

Removes the match (manual or auto); the session returns to `PLANNED`. Used to correct a wrong
automatic match or to re-assign. Does not emit `SessionMissed` immediately — the scheduler decides
that when the window elapses.

### `PUT /api/plans/{planId}/sessions/{sessionId}/skip` — user skips a session

Marks a planned session `SKIPPED` (the athlete deliberately didn't do it). No event in MVP — it is
a state change visible in the calendar, not broadcast.

## Errors

| Status | When |
|---|---|
| `200 OK` / `201 Created` | read / create |
| `204 No Content` | match cleared, skip set |
| `400 Bad Request` | invalid plan (end before start, unknown SportType/TargetType) — RFC 7807 |
| `401 Unauthorized` | missing/invalid JWT |
| `404 Not Found` | plan/session/workout absent or not owned by caller (indistinguishable) |
| `409 Conflict` | optimistic-lock conflict, or matching a workout already matched elsewhere |

## What is intentionally NOT in this API (MVP)

- **No match-create endpoint** beyond the manual override — automatic matches come from consuming
  `WorkoutCreated`, not from client writes.
- **No AI/template plan generation.** MVP plans are user-authored; generated plans are post-MVP.
- **No TSS-compliance endpoint.** Actual-TSS-accurate compliance is a deferred enhancement (would
  read Analytics' published port); MVP compliance is dimension-similarity only.
- **No cross-user / coach views.** A coach seeing an athlete's plans is post-MVP (rides on the
  multi-source identity model, not built yet).
- **No reminder-scheduling endpoint.** `ScheduledSessionDue` timing is engine-internal
  (`SessionSchedulerService`), not client-configurable in MVP beyond the session's `localTime`.

## Next documents in this BC

- Sequence diagram: `diagrams/sequence/planned-vs-actual-matching.md`
- [domain-model.md](domain-model.md), [database.md](database.md), [events.md](events.md) — already written
