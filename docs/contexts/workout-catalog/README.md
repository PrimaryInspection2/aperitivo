# Bounded Context: Workout Catalog

**Responsibility:** The canonical, sport-aware model of a completed workout — tiers 1–2 (summary + bounded structured collections). Single source of truth for "what happened and how was it structured": distance, duration, sport, laps, segment efforts, best efforts, route polyline.

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — Workout aggregate, the tier boundary, Hibernate association toolkit
- [Database Schema](database.md) — Postgres tables, indexes, fetch plans, L2 cache evaluation
- [Events](events.md) — published `WorkoutCreated/Updated/Deleted`; consumed `ActivityIngested/Deleted`
- [API](api.md) — feed (projection) and detail (entity-graph) endpoints
- [Sequence: activity normalization](diagrams/sequence/activity-normalization.md)

## Summary

The Catalog BC owns the **normalized, provider-agnostic** workout model. A `Workout` here has no knowledge of Strava's specific JSON shape — Ingestion archives the raw payload, and Catalog normalizes it (reading the archive by `rawPayloadId`) into the canonical aggregate.

This is **the BC where the full Hibernate association toolkit is architecturally correct** — `@OneToMany`, `@BatchSize`, entity graphs, cascades, `orphanRemoval`, optimistic locking — applied *within* the `Workout` aggregate to its bounded tier-2 collections.

### The tier boundary (the defining decision)

A Strava activity decomposes into three tiers of very different scale, and Catalog owns only the first two:

- **Tier 1 — summary** (one row: distance, time, avg/max HR, elevation, sport).
- **Tier 2 — bounded structured collections** (laps, segment efforts, best efforts — units to low hundreds). Modelled as `@OneToMany` *inside* the aggregate. This is the legitimate playground.
- **Tier 3 — per-second streams** (HR/watts/cadence/velocity, 10³–10⁴ samples). **NOT in Catalog.** These live in Performance Analytics' TimescaleDB hypertable. Modelling them as a JPA `@OneToMany` is documented in [domain-model.md](domain-model.md) as an explicit **rejected anti-pattern**.

The GPS route is the one tier-3-shaped datum kept in Catalog — as an **encoded polyline string** (Strava pre-encodes it; it is read whole to draw a map, not aggregated). Numeric per-second streams go to Analytics.

## Key invariants

- Every `Workout` has exactly one owning `user_id` (plain id-ref to IAM, no FK).
- One `Workout` per `(provider, providerActivityId)` — the idempotency constraint.
- Tier-2 children (laps/efforts) are composition: they exist only as part of their workout (cascade + `orphanRemoval`).
- **No tier-3 sample data in this BC** — enforced by the absence of any such table and by `ApplicationModules.verify()`.
- No JPA association crosses the aggregate or BC boundary; `userId`, `rawPayloadId`, `segmentId` are plain id-refs.

## Events published

- `WorkoutCreated` (`workout-created.v1`)
- `WorkoutUpdated` (`workout-updated.v1`)
- `WorkoutDeleted` (`workout-deleted.v1`)

Split into three (unlike Ingestion's merged `activity-ingested` + `aspectType`) because downstream reactions differ — see [events.md](events.md).

## Events consumed

- `ActivityIngested` (from Ingestion) — `aspectType` selects create vs update
- `ActivityDeleted` (from Ingestion)
