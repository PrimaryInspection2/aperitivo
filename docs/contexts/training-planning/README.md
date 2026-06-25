# Bounded Context: Training Planning

**Responsibility:** Forward-looking. Plans, scheduled sessions, planned-vs-actual reconciliation.

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — *to be written* — Plan, ScheduledSession, PlannedTarget, Compliance
- [API](api.md) — *to be written* — plan CRUD, calendar view, match overrides
- [Events](events.md) — *to be written*
- [Database Schema](database.md) — *to be written*

## Summary

The Planning BC is the only **forward-looking** BC in Aperitivo. Catalog and Analytics deal with the past; Planning deals with the future and reconciles it with the past as workouts come in.

A `Plan` is a multi-week structured program. Each `ScheduledSession` belongs to a date and has one or more `PlannedTarget`s (distance, duration, pace zone, power zone, TSS).

When a `WorkoutCreated` event arrives, Planning attempts to match it to a pending `ScheduledSession` based on date proximity + sport + target similarity. The match produces a `Compliance` score and either a `SessionCompleted` or (eventually) `SessionMissed` event. A later `WorkoutUpdated` or `WorkoutDeleted` re-evaluates an existing match.

## Key invariants

- A `ScheduledSession` matches at most one `Workout`.
- A `Workout` matches at most one `ScheduledSession` (though one match per Workout per plan; if a user has multiple active plans, this may need re-evaluation).
- Manual matching override always takes precedence over algorithmic match.

## Events published

- `ScheduledSessionDue`
- `SessionCompleted`
- `SessionMissed`

## Events consumed

- `WorkoutCreated`, `WorkoutUpdated`, `WorkoutDeleted` (from Catalog)

## Key technical concerns

- Matching algorithm: needs to handle ambiguity (athlete does 2 runs on the same day).
- Time zones: scheduled session time is in the athlete's local time zone; workout `start_date_local` from Strava is what we match against.
- Plan generation vs import: MVP supports manual plan creation; AI-generated plans deferred.
