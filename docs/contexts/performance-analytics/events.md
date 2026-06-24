# Performance Analytics — Events

Events the Performance Analytics BC **consumes** and **publishes**. Conventions:
[events/conventions.md](../../events/conventions.md), [ADR 0008](../../adr/0008-event-transport.md).
Entities and computation: [domain-model.md](domain-model.md).

Analytics is **consume-heavy**: it reacts to the workout lifecycle and publishes only one outward
fact (a personal record). It is a pure downstream BC — it never talks to Strava and emits nothing
that re-enters the ingestion path.

## What Analytics consumes

| Event | Logical name | From | Handler action |
|---|---|---|---|
| `WorkoutCreatedEvent` | `workout-created.v1` | Catalog | materialize streams, compute load, update curve/PRs |
| `WorkoutUpdatedEvent` | `workout-updated.v1` | Catalog | **re-materialize** streams, **recompute** load/curve forward from the day |
| `WorkoutDeletedEvent` | `workout-deleted.v1` | Catalog | delete the activity's samples, recompute load forward from the day |

Contracts owned by Catalog ([workout-catalog/events.md](../workout-catalog/events.md)). This is
where Catalog's decision to **split** create/update/delete pays off — Analytics genuinely does
three different things:

```java
@ApplicationModuleListener
void on(WorkoutCreatedEvent e) {
    var payload = rawPayloadReader.read(e.rawPayloadId());
    streamMaterialization.materialize(e, payload);        // bulk insert samples (idempotent)
    trainingLoad.applyNewWorkout(e);                      // add load, roll CTL/ATL/TSB forward
    personalRecords.evaluate(e, payload);                 // may emit PersonalRecordSetEvent
}

@ApplicationModuleListener
void on(WorkoutUpdatedEvent e) {
    var payload = rawPayloadReader.read(e.rawPayloadId());
    streamMaterialization.rematerialize(e, payload);      // delete-by-activity + reinsert
    trainingLoad.recomputeFrom(e.userId(), e.startedAt());// corrected load → recompute downstream
    personalRecords.reevaluate(e, payload);               // a PR may have been gained or lost
}

@ApplicationModuleListener
void on(WorkoutDeletedEvent e) {
    streamMaterialization.deleteFor(e);                   // drop the activity's samples
    trainingLoad.recomputeFrom(e.userId(), e.startedAt());// removed load → recompute downstream
    personalRecords.reevaluateAfterDeletion(e);           // a PR set by this workout may fall back
}
```

### Why update/delete are not no-ops — the recompute chain

CTL/ATL/TSB at day *D* depend on every day ≤ *D*. So changing or removing one workout's load on
day *D₀* invalidates every derived value from *D₀* to today. That is the whole reason these are
distinct events rather than a single "workout changed": the **handler's blast radius differs** —
a create folds load *in*, an update *corrects* a value already counted, a delete *removes* it, and
update/delete both trigger a forward recompute that a create does not. A PR is symmetric: an update
can set a new PR *or* invalidate one the old version held; a delete can drop a PR back to the prior
best.

## What Analytics publishes

| Event | Logical name | Emitted by | When |
|---|---|---|---|
| `PersonalRecordSetEvent` | `personal-record-set.v1` | `PersonalRecordService` | a new best was achieved (a PR row was set or beaten) |

```java
public record PersonalRecordSetEvent(
    UUID    userId,
    SportType sportType,
    String  recordType,           // '5k', 'best_20min_power', ...
    double  value,
    double  previousValue,        // what it beat (for "you improved by X" messaging)
    UUID    workoutId,
    long    providerActivityId,
    Instant achievedAt,
    Instant occurredAt
) {}
```

Consumer: **Notifications** ("🎉 New 5k PR: 23:41, 18s faster"). The event carries both new and
previous value so Notifications needs no callback read. Dedup key for the consumer:
`(userId, sportType, recordType, providerActivityId)`.

This is the **only** event Analytics publishes. Fitness/form values (CTL/ATL/TSB), curves, and
stream slices are **read via the API**, not broadcast — they change on essentially every workout
and have no discrete "fact happened" worth an event. A PR, by contrast, *is* a discrete
celebratory fact with a clear consumer. The line is the same one drawn project-wide: broadcast
discrete domain facts, serve continuous state via queries.

## Guarantees and emit conditions

1. **At-least-once consumption; idempotent handlers.** Re-delivered `WorkoutCreated` →
   materialization is delete-by-activity + reinsert (no duplicate samples); load recompute is an
   idempotent upsert on `(user_id, day)`; PR evaluation is idempotent (setting the same best is a
   no-op). So redelivery is safe end-to-end.
2. **Out-of-order tolerance.** An `update` for an activity not yet materialized is handled as a
   create; a `delete` before the matching create is a no-op (nothing to remove) — the recompute
   still runs harmlessly. Analytics never assumes Catalog's events arrive in order.
3. **Transactional publish.** `PersonalRecordSetEvent` is published from inside the PR-update
   transaction (Modulith Outbox) — the row write and the event commit together.
4. **No synthetic event id.** Identity is the business key; Modulith owns publication identity.
5. **`occurredAt`** is the domain time the record was achieved (the workout's time), set in the
   emitting transaction.

## What is deliberately NOT an event here

- **CTL/ATL/TSB changes.** Continuous derived state, read via API, not a per-day event storm.
- **Stream materialization completed.** Internal mechanic; downstream cares about PRs and reads
  charts on demand, not about when samples landed.
- **Curve updates.** Same — read on demand.
- **Recompute-from-date runs.** Internal; a recompute that changes nothing emits nothing, one that
  sets a PR emits the ordinary `PersonalRecordSetEvent`.

## Next documents in this BC

- [api.md](api.md) — stream slice, fitness/form chart, power curve, PR endpoints
- Sequence diagram: `diagrams/sequence/stream-materialization.md`
- [domain-model.md](domain-model.md), [database.md](database.md) — already written
