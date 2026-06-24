# Sequence: Workout Catalog — Activity Normalization

How a `Workout` aggregate is built from an ingested activity: consuming Ingestion's
`ActivityIngestedEvent`, reading the archived raw payload through a published port, normalizing
tiers 1–2 into the aggregate, and emitting `WorkoutCreated`/`WorkoutUpdated`. Tier-3 streams are
**not** touched here — that branch belongs to Analytics
([performance-analytics](../../../performance-analytics/diagrams/sequence/stream-materialization.md)).

Contracts: [domain-model.md](../../domain-model.md), [events.md](../../events.md),
[database.md](../../database.md).

## What this diagram shows

1. **Event-driven creation** — a `Workout` is never created by an HTTP write; it is the product
   of consuming `ActivityIngestedEvent` after Ingestion commits.
2. **Read from the archive, not from Strava** — normalization reads `RawActivityPayload` via
   Ingestion's published `RawPayloadReader` port using `rawPayloadId`. No Strava call, no
   rate-limit cost; a normalization bug is replayable over the archive.
3. **create vs update by aspect** — one handler branches on `aspectType`; the upsert key
   `(provider, providerActivityId)` makes redelivery idempotent.
4. **One transaction for persist + publish** — the aggregate write and the outbound event are
   atomic (Modulith Outbox); tier-2 children cascade with the root.

## Diagram

```mermaid
sequenceDiagram
    autonumber
    participant ING as Ingestion<br/>(event_publication)
    participant NS as ActivityNormalizationService
    participant RPR as RawPayloadReader<br/>(Ingestion published port)
    participant WR as WorkoutRepository
    participant DB as catalog schema
    participant EP as event_publication<br/>(Catalog outbox)
    participant AN as Analytics (downstream)

    ING-->>NS: @ApplicationModuleListener ActivityIngestedEvent<br/>{provider, providerActivityId, rawPayloadId, aspectType, userId}
    Note over NS: AFTER_COMMIT of Ingestion's write —<br/>at-least-once; handler must be idempotent

    NS->>RPR: read(rawPayloadId)
    RPR-->>NS: raw Strava activity JSON (from archive, no Strava call)

    Note over NS,DB: TX — build/upsert aggregate AND publish, atomically
    rect rgb(238, 248, 238)
        alt aspectType = create
            NS->>WR: findByProviderActivity(provider, providerActivityId)
            alt not present (true create)
                NS->>NS: map tier-1 summary + tier-2 laps/efforts/bestEfforts<br/>+ encoded polyline (tier-3 streams IGNORED here)
                NS->>WR: save(WorkoutEntity + children)
                WR->>DB: INSERT workouts (+ cascade laps, segment_efforts, best_efforts)
                NS->>EP: publish WorkoutCreatedEvent{workoutId, …, rawPayloadId, occurredAt}
            else already present (redelivery)
                Note over NS: idempotent — treat as update or no-op,<br/>uq_workouts_provider_activity already holds
                NS->>EP: (no duplicate create)
            end
        else aspectType = update
            NS->>WR: findByProviderActivity(provider, providerActivityId)
            NS->>NS: re-map summary + rebuild child collections<br/>(orphanRemoval clears replaced laps/efforts)
            NS->>WR: save(WorkoutEntity)  %% @Version guards concurrent re-normalization
            WR->>DB: UPDATE workouts; DELETE+INSERT children via orphanRemoval
            NS->>EP: publish WorkoutUpdatedEvent{…, rawPayloadId, occurredAt}
        end
    end

    EP-->>AN: AFTER_COMMIT delivery → Analytics reads tier-3 streams<br/>from archive by rawPayloadId (separate flow)
    Note over AN: Catalog handed off rawPayloadId; Analytics owns<br/>per-second materialization. Catalog never stored a sample.
```

## Notes keyed to the locked decisions

- **Tier-3 is conspicuously absent.** The normalization step maps summary, laps, segment efforts,
  best efforts, and the encoded polyline — and *ignores* the per-second arrays in the payload.
  Those same arrays are read by Analytics from the same archive. Catalog inserts zero stream rows;
  the boundary is enforced by there being no such table ([database.md](../../database.md)).
- **`orphanRemoval` on update.** Re-normalizing an edited activity rebuilds the child collections;
  laps removed from the in-memory list are deleted by `orphanRemoval`, new ones inserted. The
  aggregate root's `@Version` serializes a concurrent re-normalization of the same activity.
- **Read port, not HTTP.** Reaching the raw payload uses Ingestion's in-process published
  `RawPayloadReader`, not a network call to Ingestion's API — inter-BC data flow is events +
  published ports, never the public HTTP surface ([api.md](../../api.md)).
- **Atomic persist + publish.** The `Workout` write and the `WorkoutCreated/Updated` publication
  share one transaction via Modulith's Outbox; a crash before commit leaves the event undelivered
  and the listener re-runs on the redelivered `ActivityIngestedEvent` — idempotent by the upsert
  key.
- **create/update stay distinct downstream.** Catalog emits two event types (unlike Ingestion's
  merged one) because Analytics reacts differently — new load vs corrected load
  ([events.md](../../events.md)).
