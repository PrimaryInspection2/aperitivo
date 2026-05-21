# Bounded Context: Performance Analytics

**Responsibility:** Derived metrics over time. Training load, fitness/fatigue/form (CTL/ATL/TSB), personal records, trends, power/pace curves. Read-heavy, recomputed.

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — *to be written* — TSS computation per sport, CTL/ATL/TSB algorithms, PR detection
- [API](api.md) — *to be written* — fitness curves, PRs, trends, time-window queries
- [Events](events.md) — *to be written*
- [Database Schema](database.md) — *to be written* — TimescaleDB hypertables, continuous aggregates

## Summary

Performance Analytics is the BC that turns raw workouts into **meaning**. It is conceptually the read side of a CQRS-like split:
- Catalog owns the **write model** ("here's what happened").
- Analytics owns the **read model and derivations** ("here's what it means").

Recomputation is event-driven: a `WorkoutPublished` triggers updates to the affected athlete's time-series projections.

**TimescaleDB lives here** and only here. No other BC uses time-series storage.

## Key concepts

- **TSS (Training Stress Score):** single-workout intensity × duration metric. Per-sport formulas (rTSS for running, TSS for cycling, sTSS for swimming).
- **CTL (Chronic Training Load):** 42-day exponentially weighted moving average of TSS. Proxies fitness.
- **ATL (Acute Training Load):** 7-day EWMA of TSS. Proxies fatigue.
- **TSB (Training Stress Balance):** CTL − ATL. Form indicator.
- **PR (Personal Record):** best-ever performance over specified distance/duration/sport.

## Events published

- `MetricsRecomputed`
- `PersonalRecordDetected`
- `TrendDetected`

## Events consumed

- `WorkoutPublished`, `WorkoutUpdated`, `WorkoutDeleted` (from Catalog)

## Key technical concerns

- Recomputation idempotency: receiving the same `WorkoutPublished` twice must not double-count TSS.
- Backfill performance: when a user first signs up, processing thousands of historical workouts must not block other operations.
- Retention: raw daily TSS retained indefinitely; high-resolution intermediate computations may use continuous aggregates with downsampling.
