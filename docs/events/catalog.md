# Event Catalog

This is the canonical catalog of all **domain events** that flow between Bounded Contexts in Aperitivo. It is the single source of truth for event names, ownership, payload contracts, and consumer relationships.

Events are delivered **in-process** via Spring Modulith application events (persisted in `event_publication`, published after commit). There is no message broker in the MVP — see [ADR 0008](../adr/0008-event-transport.md). The events are nonetheless designed to be **broker-ready**: past-tense names, explicit versioning, and `user_id` in every payload (the natural partition key if they are ever externalized).

> **Status: skeleton.** Detailed payload schemas will be filled in during the event-storming pass for each BC. For now this lists known events with their publishers, consumers, and triggers.

## Conventions

See [conventions.md](conventions.md) for the full set. In brief:

- **Past tense, domain language.** `WorkoutPublished`, not `PublishWorkout`; `IntegrationRevoked`, not `StravaTokenInvalidated`.
- **Versioned contracts.** Each event has a contract version; backward-compatible changes keep the version, breaking changes bump it.
- **One publishing BC per event.** Any BC may consume. A BC consuming its own events is a smell.
- **At-least-once semantics in spirit.** Even in-process, consumers that re-publish are made idempotent on `event_id` (Inbox pattern), so that externalizing to a broker later requires no rework.

## Event index

### Published by Identity & Access

| Event | Version | Triggered by | Consumed by |
|---|---|---|---|
| `UserRegistered` | v1 | First Strava sign-up | (future: analytics) |
| `IntegrationConnected` | v1 | New ConnectedSource created | Activity Ingestion (kicks off backfill) |
| `IntegrationRevoked` | v1 | Token rejected by Strava, or deauth webhook | Activity Ingestion (cancel jobs), Notifications |

### Published by Activity Ingestion

| Event | Version | Triggered by | Consumed by |
|---|---|---|---|
| `ActivityIngested` | v1 | Successful sync of one activity | Workout Catalog |
| `ActivityDeleted` | v1 | Strava notified of deletion | Workout Catalog |
| `IngestionFailed` | v1 | Sync job exhausted retries | Notifications |

### Published by Workout Catalog

| Event | Version | Triggered by | Consumed by |
|---|---|---|---|
| `WorkoutPublished` | v1 | New canonical Workout persisted | Performance Analytics, Training Planning |
| `WorkoutUpdated` | v1 | Strava update synced | Performance Analytics |
| `WorkoutDeleted` | v1 | Activity deletion processed | Performance Analytics, Training Planning |

### Published by Performance Analytics

| Event | Version | Triggered by | Consumed by |
|---|---|---|---|
| `MetricsRecomputed` | v1 | After processing a WorkoutPublished | (future: UI cache invalidation) |
| `PersonalRecordDetected` | v1 | New PR found | Notifications |
| `TrendDetected` | v1 | Multi-week directional change | Notifications |

### Published by Training Planning

| Event | Version | Triggered by | Consumed by |
|---|---|---|---|
| `ScheduledSessionDue` | v1 | Scheduled time approached | Notifications |
| `SessionCompleted` | v1 | Match found between plan and workout | Notifications |
| `SessionMissed` | v1 | No match within window | Notifications |

### Published by Notifications

| Event | Version | Triggered by | Consumed by |
|---|---|---|---|
| `NotificationDelivered` | v1 | Successful delivery | (future: analytics) |
| `NotificationFailed` | v1 | Permanent delivery failure | (future: alerting) |

---

## Common envelope

All events share a common envelope:

```json
{
  "event_id": "uuid",
  "event_type": "WorkoutPublished",
  "event_version": "v1",
  "occurred_at": "ISO-8601 timestamp",
  "producer": "workout-catalog",
  "trace_id": "for OTel correlation",
  "user_id": "uuid (partition key if ever externalized)",
  "data": { /* event-specific payload */ }
}
```

The `user_id` in the envelope is the Aperitivo internal UUID, not a provider-specific ID. It is carried even though events are in-process today, so that externalizing to a partitioned broker later is a configuration change rather than a contract change.

---

## Detailed schemas

*To be filled in. Each event will have its own subsection here with the full payload schema, an example payload, and a brief description of when it's emitted and what invariants hold.*
