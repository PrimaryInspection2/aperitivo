# Bounded Context: Training Planning

**Responsibility:** Forward-looking. Plans, scheduled sessions, planned-vs-actual reconciliation.

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — Plan aggregate, ScheduledSession, PlannedTarget, the matching engine, the TSS decoupling
- [Database Schema](database.md) — plans/sessions/targets, match & compliance storage, indexes
- [Events](events.md) — published `ScheduledSessionDue/SessionCompleted/SessionMissed`; consumed `WorkoutCreated/Updated/Deleted`
- [API](api.md) — plan CRUD, calendar view, manual match override
- [Sequence: planned-vs-actual matching](diagrams/sequence/planned-vs-actual-matching.md)

## Summary

The Planning BC is the only **forward-looking** BC in Aperitivo. Catalog and Analytics deal with the past; Planning deals with the future and reconciles it with the past as workouts come in.

A `Plan` is a multi-week structured program. Each `ScheduledSession` belongs to a date and has one or more `PlannedTarget`s (distance, duration, pace zone, power zone, TSS). Like Catalog, this BC legitimately exercises the **Hibernate association toolkit within its aggregate** — a plan is a real composition of sessions, and sessions of targets.

When a `WorkoutCreated` event arrives, Planning attempts to match it to a pending `ScheduledSession` based on date proximity + sport + target similarity. The match produces a `Compliance` score and a `SessionCompleted` event; a later `WorkoutUpdated` re-evaluates the match and `WorkoutDeleted` un-matches it. Sessions whose window elapses unmatched become `SessionMissed`.

## The TSS decoupling (resolved)

The bounded-contexts overview previously flagged an open question: does a TSS-valued `PlannedTarget` couple Planning to Analytics? **Resolved:** a `PlannedTarget` of type `TSS` stores a **planned number that Planning owns** (authoring-time intent). MVP compliance is computed from directly-readable workout dimensions (distance/duration/pace) — Planning does **not** read Analytics' actual TSS. A TSS-accurate compliance, if ever wanted, would read Analytics via its published read port — a deferred, additive enhancement. So there is **no cross-BC coupling in the MVP matching path**; Planning runs even if Analytics is down. See [domain-model.md](domain-model.md).

## Key invariants

- A `ScheduledSession` matches at most one `Workout`, and a `Workout` matches at most one `ScheduledSession` (greedy by descending match score; resolves the "two runs on one day" ambiguity deterministically).
- Manual matching override always takes precedence over the algorithm and is sticky across re-evaluation.
- Targets belong to exactly one session; sessions to exactly one plan (composition; cascade + orphanRemoval).
- Planned TSS is owned data; actual TSS is not read in MVP compliance.
- No JPA association crosses the aggregate or BC boundary; `userId` and `matchedWorkoutId` are id-refs.

## Events published

- `ScheduledSessionDue` — a planned session approaches its time (→ Notifications)
- `SessionCompleted` — a workout matched a session (→ Notifications)
- `SessionMissed` — a session's window elapsed unmatched (→ Notifications)

## Events consumed

- `WorkoutCreated`, `WorkoutUpdated`, `WorkoutDeleted` (from Catalog) — match / re-evaluate / un-match

## Key technical concerns

- Matching algorithm handles ambiguity (two runs on the same day) via best-score one-to-one assignment.
- Time zones: a session's planned time is athlete-local; matching uses the workout's `start_date_local` from Strava, with a ±1-day candidate window absorbing zone edge cases.
- Plan generation vs import: MVP supports manual plan creation; AI-generated and template plans are deferred.
