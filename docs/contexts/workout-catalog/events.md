# Workout Catalog — Events

Events the Workout Catalog BC **publishes** and **consumes**. Conventions:
[events/conventions.md](../../events/conventions.md), catalog
[events/catalog.md](../../events/catalog.md), transport [ADR 0008](../../adr/0008-event-transport.md).
Entities and emit points: [domain-model.md](domain-model.md).

## What Catalog publishes

| Event | Logical name | Emitted by | When |
|---|---|---|---|
| `WorkoutCreatedEvent` | `workout-created.v1` | `ActivityNormalizationService` | a new `Workout` was normalized and persisted from a `create`-aspect activity |
| `WorkoutUpdatedEvent` | `workout-updated.v1` | `ActivityNormalizationService` | an existing `Workout` was re-normalized from an `update`-aspect activity |
| `WorkoutDeletedEvent` | `workout-deleted.v1` | `WorkoutDeletionService` | a `Workout` was removed in response to a `delete` |

### Why `created` and `updated` are **separate** events here (unlike Ingestion)

Ingestion deliberately collapsed `create`/`update` into one `ActivityIngestedEvent` with an
`aspectType` field — because to Ingestion both are "fetch and archive a payload", structurally
identical. Catalog splits them because **downstream semantics differ**: Analytics treats a new
workout as new training load to fold into CTL/ATL, whereas an update is a *correction* to an
already-counted session that may require recomputation from that date forward. Making them
distinct events lets Analytics react differently without inspecting a discriminator field. The
guidance "merge when consumers treat them the same, split when they diverge" cuts the opposite
way in the two BCs — and that contrast is itself worth the documentation.

### Payloads

```java
public record WorkoutCreatedEvent(
    UUID    workoutId,
    UUID    userId,
    String  provider,
    long    providerActivityId,
    UUID    rawPayloadId,          // so Analytics can read tier-3 streams from the archive
    SportType sportType,
    Instant startedAt,
    int     movingTimeSec,
    double  distanceM,
    Instant occurredAt
) {}
```

```java
public record WorkoutUpdatedEvent(
    UUID    workoutId,
    UUID    userId,
    String  provider,
    long    providerActivityId,
    UUID    rawPayloadId,
    SportType sportType,
    Instant startedAt,            // may have changed (athlete edited the activity)
    int     movingTimeSec,
    double  distanceM,
    Instant occurredAt
) {}
```

```java
public record WorkoutDeletedEvent(
    UUID    workoutId,
    UUID    userId,
    String  provider,
    long    providerActivityId,
    Instant occurredAt
) {}
```

Design notes, consistent with the rest of the system:
- **`rawPayloadId` is carried, not the payload body.** Analytics needs the per-second streams to
  build its hypertable; it reads them from Ingestion's archive by this reference, exactly as the
  domain model intends (tier-3 is sourced once, from the archive, never re-fetched from Strava).
  Created/Updated carry it; Deleted does not (nothing to read).
- **Summary fields are included** (`sportType`, `startedAt`, `movingTimeSec`, `distanceM`) so a
  consumer that only needs the headline (e.g. a "you logged a 42 km run" notification) acts
  without a callback read into Catalog. Richer detail (laps/efforts) is fetched from Catalog's
  API on demand — events stay small.
- **No synthetic `eventId`.** Identity is the activity reference `(provider, providerActivityId)`;
  Modulith owns publication identity. Same policy as IAM and Ingestion.
- **`occurredAt`** is the domain time of the create/update/delete, set in the emitting service's
  transaction — distinct from Modulith's commit/publish time.

## What Catalog consumes

| Event | Logical name | From | Handler action |
|---|---|---|---|
| `ActivityIngestedEvent` | `activity-ingested.v1` | Ingestion | `ActivityNormalizationService` reads the payload, builds/updates the `Workout` |
| `ActivityDeletedEvent` | `activity-deleted.v1` | Ingestion | `WorkoutDeletionService` removes the `Workout` |

Contracts owned by Ingestion ([activity-ingestion/events.md](../activity-ingestion/events.md)),
not redefined here. The `aspectType` on `ActivityIngestedEvent` selects create-vs-update inside
the handler:

```java
@ApplicationModuleListener
void on(ActivityIngestedEvent e) {
    var payload = rawPayloadReader.read(e.rawPayloadId());   // Ingestion's published read port
    switch (e.aspectType()) {
        case "create" -> normalizationService.createFrom(e, payload);  // → WorkoutCreatedEvent
        case "update" -> normalizationService.updateFrom(e, payload);  // → WorkoutUpdatedEvent
    }
}
```

## Guarantees and emit conditions

1. **At-least-once, both directions.** Modulith Outbox (`event_publication`) delivers Ingestion's
   events to Catalog after commit, retrying until the listener completes; Catalog's own events
   are published the same way. Consumers must be idempotent.
2. **Idempotent normalization.** The `uq_workouts_provider_activity` constraint makes a
   re-delivered `ActivityIngestedEvent` an upsert, not a duplicate `Workout`. A redelivered
   `create` after the row exists resolves to the existing workout (treated as update or no-op).
3. **Catalog's emit is transactional with the write.** `WorkoutCreatedEvent` is published from
   inside the same `@Transactional` that persists the `Workout` — Modulith writes the publication
   row in that transaction (Outbox). Roll back → no event; commit → durable event.
4. **Ordering only where it matters.** Events carry `userId`; per single activity, create
   precedes update precedes delete naturally (each gated by Strava's own event order and by the
   prior `Workout` existing). Analytics tolerates out-of-order arrival idempotently (an update
   for an unknown workout, or a delete before create, are handled gracefully).
5. **Dedup keys for consumers.** Analytics/Notifications dedup on `(provider, providerActivityId)`
   (+ `aspectType` where create/update must be distinguished). No synthetic id needed.

## What is deliberately NOT an event here

- **Normalization started / payload read.** Internal mechanics, not domain facts.
- **Lap/effort-level changes.** The aggregate is the unit; a re-normalization emits one
  `WorkoutUpdatedEvent`, not per-child diffs. Downstream cares about the workout, not its laps.
- **Feed/detail reads.** Queries are not events.
- **L2 cache evictions, projection refreshes.** Pure persistence-internal concerns.

## Next documents in this BC

- [api.md](api.md) — feed (projection) and detail (entity-graph) endpoints
- Sequence diagram: `diagrams/sequence/activity-normalization.md`
- [domain-model.md](domain-model.md), [database.md](database.md) — already written
