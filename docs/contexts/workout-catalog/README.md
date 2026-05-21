# Bounded Context: Workout Catalog

**Responsibility:** The canonical, sport-aware model of a completed workout. Single source of truth for "what happened" — distance, duration, sport, splits, streams, route.

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — *to be written* — Workout aggregate, Sport hierarchy, Split, Stream
- [API](api.md) — *to be written* — listing, detail, stream retrieval
- [Events](events.md) — *to be written*
- [Database Schema](database.md) — *to be written* — Postgres tables, stream storage strategy

## Summary

The Catalog BC owns the **normalized, provider-agnostic** workout model. A `Workout` here has no knowledge of Strava's specific JSON shape — that's Ingestion's job to normalize before publishing `ActivityIngested`.

This is also the BC that decides what's worth storing structured (splits, summary metrics) vs bulk (streams as compressed blobs vs separate stream store).

## Key invariants

- Every `Workout` has exactly one owning `user_id`.
- Sport is a discriminator that controls which sport-specific fields are populated.
- Once published, the canonical `Workout` may be updated (athlete renames in Strava → `WorkoutUpdated`) but the identity never changes.

## Events published

- `WorkoutPublished`
- `WorkoutUpdated`
- `WorkoutDeleted`

## Events consumed

- `ActivityIngested` (from Ingestion)
- `ActivityDeleted` (from Ingestion)
