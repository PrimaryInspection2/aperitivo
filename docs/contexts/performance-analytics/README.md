# Bounded Context: Performance Analytics

**Responsibility:** Derived metrics over time, and the tier-3 per-second telemetry store. Training load, fitness/fatigue/form (CTL/ATL/TSB), personal records, power/pace curves. The complement to Catalog: Catalog answers "which workouts", Analytics answers "what do they mean over time".

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — stream materialization, CTL/ATL/TSB, PR detection, the two storage shapes
- [Database Schema](database.md) — TimescaleDB hypertable + relational derived-metric tables
- [Events](events.md) — consumed `WorkoutCreated/Updated/Deleted`; published `PersonalRecordSet`
- [API](api.md) — stream slice, fitness/form chart, power curve, PR endpoints
- [Sequence: stream materialization & recompute](diagrams/sequence/stream-materialization.md)
- [TimescaleDB schema deep-dive](../../technical-notes/timescaledb-schema.md)

## Summary

Performance Analytics is conceptually the read/derivation side of a CQRS-like split:
- Catalog owns the canonical workout structure (tiers 1–2).
- Analytics owns the **tier-3 per-second streams** and everything **derived** from the body of a training history.

It is a **pure downstream consumer** — it never talks to Strava; it reads the raw archive (by `rawPayloadId`, via Ingestion's published `RawPayloadReader` port) and reacts to Catalog's workout events.

### Two storage shapes (the defining decision)

Analytics is the deliberate **counterweight** to Catalog: same project, opposite toolkit, because the data shape is opposite.

- **Tier-3 samples → TimescaleDB hypertable** (`activity_samples`). Bulk-inserted (`COPY`/batch), queried with `time_bucket`/aggregates, **never** an ORM collection. This is where the time-series tooling lives.
- **Derived metrics → small relational JPA entities** (`daily_training_load` for CTL/ATL/TSB, `personal_records`, `activity_power_curve`). Plain CRUD, `@Version`, **no associations**.

So the Hibernate **association** toolkit that Catalog showcases is deliberately **off** here — the heavy data is time-series, the light data is flat. **TimescaleDB lives here and only here.**

## Key concepts

- **Training load** (TSS-like): power→TSS (cycling), HR→TRIMP (most sports), duration fallback.
- **CTL (fitness):** ~42-day EWMA of daily load. **ATL (fatigue):** ~7-day EWMA. **TSB (form):** CTL − ATL.
- **Recompute-forward-from-date:** an updated or deleted workout changes its day's load and every CTL/ATL/TSB value from that date onward — this is *why* Catalog splits create/update/delete into distinct events.
- **PR / power curve:** best efforts seed PRs; the power/pace curve is computed from the streams.

## Events published

- `PersonalRecordSet` (`personal-record-set.v1`) → Notifications. Carries `value` + `previousValue`.

This is the **only** event Analytics publishes. CTL/ATL/TSB, curves, and stream slices are **read via the API**, not broadcast — continuous state is queried, discrete facts are events. (Earlier drafts listed `MetricsRecomputed` and `TrendDetected`; both dropped — see [events.md](events.md) and [event catalog](../../events/catalog.md).)

## Events consumed

- `WorkoutCreated`, `WorkoutUpdated`, `WorkoutDeleted` (from Catalog)

## Key technical concerns

- **Recomputation idempotency:** a re-delivered `WorkoutCreated` must not double-count load. Materialization is delete-by-activity + reinsert; load recompute upserts `(user_id, day)`; PR evaluation is idempotent.
- **Backfill performance:** a new user's thousands of historical workouts must not block other operations — each arrives as an ordinary `WorkoutCreated`.
- **Retention:** raw samples are reconstructable from Ingestion's archive, so old hypertable chunks can be compressed (and, post-MVP, retention-dropped) without data loss.
