# Training Planning — Events

Events the Training Planning BC **publishes** (session lifecycle) and **consumes** (the workout
lifecycle from Catalog). Conventions: [events/conventions.md](../../events/conventions.md), catalog
[events/catalog.md](../../events/catalog.md). Domain model and matching:
[domain-model.md](domain-model.md).

## What Planning publishes

| Event | Logical name | Emitted by | When |
|---|---|---|---|
| `ScheduledSessionDueEvent` | `scheduled-session-due.v1` | `SessionSchedulerService` | a `PLANNED` session approaches its scheduled time |
| `SessionCompletedEvent` | `session-completed.v1` | `SessionMatchingService` | a workout was matched to a session (auto or manual) |
| `SessionMissedEvent` | `session-missed.v1` | `SessionSchedulerService` | a session's window elapsed with no match |

All three are consumed by **Notifications**. They are the user-facing reminders and feedback of the
planning loop.

### Payloads

```java
public record ScheduledSessionDueEvent(
    UUID    scheduledSessionId,
    UUID    userId,
    UUID    planId,
    SportType sportType,
    LocalDate sessionDate,
    String  localTime,           // "HH:mm" athlete-local, nullable
    Instant occurredAt
) {}

public record SessionCompletedEvent(
    UUID    scheduledSessionId,
    UUID    userId,
    UUID    planId,
    UUID    matchedWorkoutId,    // id-ref to the Catalog workout
    double  complianceScore,     // overall similarity 0..1
    boolean manualMatch,         // true if the user linked it explicitly
    Instant occurredAt
) {}

public record SessionMissedEvent(
    UUID    scheduledSessionId,
    UUID    userId,
    UUID    planId,
    SportType sportType,
    LocalDate sessionDate,
    Instant occurredAt
) {}
```

Design notes, consistent with the rest of the system:
- **Id-refs, not embedded data.** `matchedWorkoutId` references the Catalog workout; Notifications
  (or any consumer) reads workout detail from Catalog if it needs more than the score. Events stay
  small.
- **`complianceScore` is carried** so Notifications can render "you nailed it" vs "a bit short"
  without a callback. Per-target deltas stay in Planning (queried via API), not in the event.
- **`manualMatch`** lets a consumer distinguish an auto-match from a user-confirmed one (different
  messaging tone).
- **No synthetic `eventId`.** Identity is `scheduledSessionId` (+ `matchedWorkoutId` for
  completion); Modulith owns publication identity. Dedup keys for Notifications are documented in
  [notifications/events.md](../notifications/events.md).
- **`occurredAt`** is the domain time (due time / match time / miss-detection time), set in the
  emitting transaction.

## What Planning consumes

| Event | Logical name | From | Handler action |
|---|---|---|---|
| `WorkoutCreatedEvent` | `workout-created.v1` | Catalog | run candidate selection + scoring; on match → set match, compute compliance, emit `SessionCompleted` |
| `WorkoutUpdatedEvent` | `workout-updated.v1` | Catalog | **re-evaluate** the affected workout's match (a corrected workout may match better, or differently) |
| `WorkoutDeletedEvent` | `workout-deleted.v1` | Catalog | **un-match** — if this workout fulfilled a session, clear the match; the session returns to `PLANNED` (and may later go `MISSED`) |

Contracts owned by Catalog ([workout-catalog/events.md](../workout-catalog/events.md)). This is the
consuming side of Catalog's deliberate create/update/delete split — Planning, like Analytics, reacts
**differently** to each:

```java
@ApplicationModuleListener
void on(WorkoutCreatedEvent e) {
    matching.matchNewWorkout(e);        // candidate select → score → match? → SessionCompleted
}

@ApplicationModuleListener
void on(WorkoutUpdatedEvent e) {
    matching.reevaluate(e);             // the workout's dimensions changed; re-score its match
}

@ApplicationModuleListener
void on(WorkoutDeletedEvent e) {
    matching.unmatch(e.workoutId());    // clear matched_workout_id; session back to PLANNED
}
```

### Why update/delete are not no-ops

A `WorkoutUpdated` can change the very dimensions matching uses (distance/duration/sport) — an
edited activity might now fit a *different* session, or fit the same one with a different compliance
score. A `WorkoutDeleted` removes a fulfillment: the session it matched must revert to `PLANNED`
(and the scheduler may subsequently mark it `MISSED` if its window has passed). Collapsing these
into one "workout changed" event would force Planning to diff to find its reaction — the split
carries that intent in the event type, exactly as it does for Analytics.

## What Planning reads, but not via events

- **Workout detail for scoring.** The match needs the workout's distance/duration/sport. These ride
  on the `WorkoutCreated` event payload (Catalog includes summary fields); richer detail, if needed,
  is read from **Catalog's API/published port**, never by joining Catalog's tables.
- **Actual TSS** — **not read in MVP.** Per the decoupling ([domain-model.md](domain-model.md)),
  MVP compliance uses directly-readable dimensions only. A post-MVP TSS-accurate compliance would
  read actual TSS via Analytics' published read port — a deferred enhancement, not an event.

## Guarantees and emit conditions

1. **At-least-once consumption; idempotent matching.** Re-delivered `WorkoutCreated` → matching is
   idempotent: a workout already matched to a session is recognized (by `matched_workout_id`) and
   not double-counted.
2. **Transactional publish.** `SessionCompleted` is published in the same transaction that writes
   the match/compliance (Outbox). `ScheduledSessionDue`/`SessionMissed` likewise with their status
   writes.
3. **Out-of-order tolerance.** A `WorkoutUpdated` for a workout Planning hasn't seen is treated as a
   create (match attempt); a `WorkoutDeleted` for an unmatched workout is a no-op.
4. **Manual override is respected on re-evaluation.** A `WorkoutUpdated` does not overwrite a
   user's manual match; re-evaluation skips manually-linked sessions (invariant 2).
5. **No synthetic event id.** Business keys + Modulith publication identity.

## What is deliberately NOT an event here

- **Plan created/edited.** Authoring is a synchronous API result, not a fact other BCs react to.
  (Notifications has no "you made a plan" message in MVP.)
- **Candidate selection / scoring internals.** Pure matching mechanics.
- **Compliance recomputation that changes nothing.** A re-evaluation yielding the same match emits
  nothing new.
- **Session `SKIPPED` by the user.** A manual skip is a state change surfaced via the API; it is not
  broadcast in MVP (no consumer needs it). Could become an event if Analytics ever factors planned
  adherence into anything.

## Next documents in this BC

- [api.md](api.md) — plan CRUD, calendar, manual match override
- Sequence diagram: `diagrams/sequence/planned-vs-actual-matching.md`
- [domain-model.md](domain-model.md), [database.md](database.md) — already written
