# Notifications — Events

Events the Notifications BC **consumes** (from every other BC) and the two delivery-status events
it **publishes**. Conventions: [events/conventions.md](../../events/conventions.md), catalog
[events/catalog.md](../../events/catalog.md). Entities and handlers:
[domain-model.md](domain-model.md).

Notifications is the most consume-heavy BC in the system: it is the terminal sink of the event
flow. It publishes only delivery-status facts, consumed (today) by nothing — they exist for
observability and a possible future alerting consumer.

## What Notifications consumes

| Event | Logical name | From | NotificationType | Dedup key (`dedup_key`) |
|---|---|---|---|---|
| `PersonalRecordSetEvent` | `personal-record-set.v1` | Analytics | `PERSONAL_RECORD` | `pr:{userId}:{sportType}:{recordType}:{providerActivityId}` |
| `IntegrationRevokedEvent` | `integration-revoked.v1` | IAM | `INTEGRATION_REVOKED` | `revoked:{connectedSourceId}` |
| `IngestionFailedEvent` | `ingestion-failed.v1` | Ingestion | `INGESTION_FAILED` | `ingestfail:{provider}:{providerActivityId}` |
| `ScheduledSessionDueEvent` | `scheduled-session-due.v1` | Planning | `SESSION_DUE` | `sessiondue:{scheduledSessionId}` |
| `SessionCompletedEvent` | `session-completed.v1` | Planning | `SESSION_COMPLETED` | `sessiondone:{scheduledSessionId}:{workoutId}` |
| `SessionMissedEvent` | `session-missed.v1` | Planning | `SESSION_MISSED` | `sessionmiss:{scheduledSessionId}` |

Contracts are owned by the **producing** BC (Analytics, IAM, Ingestion, Planning) — Notifications
does not redefine them. The table above is the consumer's view: which event maps to which internal
`NotificationType`, and the natural business key used for idempotency.

### One handler per event, common pipeline

```java
@ApplicationModuleListener
void on(PersonalRecordSetEvent e) {
    dispatch.handle(
        NotificationType.PERSONAL_RECORD,
        e.userId(),
        "pr:%s:%s:%s:%d".formatted(e.userId(), e.sportType(), e.recordType(), e.providerActivityId()),
        Map.of("recordType", e.recordType(), "value", e.value(), "previousValue", e.previousValue())
    );
}
// …one analogous listener per consumed event, all funneling into dispatch.handle(...)
```

`dispatch.handle(type, userId, dedupKey, params)` is the shared pipeline:
1. **Dedup** — insert a `NotificationEntity` with `dedupKey`; a unique-constraint violation means
   already-processed → stop (idempotent no-op).
2. **Gate** — for each channel, check the user's preference (row, or code default); skip disabled.
3. **Render** — fill the `(type, channel)` template with `params`.
4. **Enqueue** — create a `DeliveryAttempt(QUEUED)` per enabled channel.

This is why the inbound contracts can differ wildly in shape while the BC stays simple: each
listener's only job is to extract `(userId, dedupKey, params)` and hand off.

### Why dedup keys are per-event

Each upstream event has a different natural identity, so the dedup key is composed per event type
(no synthetic `eventId` in MVP — [conventions.md](../../events/conventions.md)):
- A PR is identified by *which best on which activity* — `(userId, sportType, recordType, providerActivityId)`.
- A revocation is identified by *which connection* — `connectedSourceId`.
- A due-session reminder by *which scheduled session* — `scheduledSessionId`.

These keys are exactly the producers' own dedup keys, so a redelivery on either side collapses to
one notification. They are stored in `notifications.dedup_key` (unique) — see
[database.md](database.md).

## What Notifications publishes

| Event | Logical name | Emitted by | When |
|---|---|---|---|
| `NotificationDeliveredEvent` | `notification-delivered.v1` | `DeliveryService` | a `DeliveryAttempt` reached `SENT` |
| `NotificationFailedEvent` | `notification-failed.v1` | `DeliveryService` | a `DeliveryAttempt` exhausted its retries (terminal `FAILED`) |

```java
public record NotificationDeliveredEvent(
    UUID    notificationId,
    UUID    userId,
    NotificationType eventType,
    Channel channel,
    Instant occurredAt
) {}

public record NotificationFailedEvent(
    UUID    notificationId,
    UUID    userId,
    NotificationType eventType,
    Channel channel,
    String  reason,        // last error, human-readable
    Instant occurredAt
) {}
```

Consumers in MVP: **none** — these are emitted for observability (metrics/traces correlate them)
and as the seam for a future alerting consumer (e.g. "email delivery failing across many users").
They are published the same way as every other event (Modulith Outbox, after commit) so that seam
needs no rework when something starts consuming them.

## Guarantees and emit conditions

1. **At-least-once in; idempotent.** Every consumed event may arrive more than once; the
   `dedup_key` unique constraint makes processing exactly-once in effect.
2. **Out-of-order tolerant.** Notifications never assumes ordering between upstream events — each
   is handled independently. A `SessionCompleted` arriving before its `ScheduledSessionDue` is fine
   (they are separate notifications).
3. **Delivery-status events are transactional with the status write.** `NotificationDelivered`/
   `Failed` are published in the same transaction that flips the `DeliveryAttempt` status (Outbox).
4. **No synthetic event id.** Identity is the business key (the producers') / the
   `notificationId` (for delivery-status events). Modulith owns publication identity.
5. **`occurredAt`** is the delivery time (status-change time), set in the emitting transaction.

## What is deliberately NOT an event here

- **"Notification rendered" / "enqueued".** Internal pipeline mechanics, not domain facts.
- **"User read an in-app notification".** A read is a state change on the inbox row, surfaced via
  the API (unread count); no other BC cares, so it is not broadcast.
- **"SSE connection opened/closed".** Transport lifecycle, observed via metrics, not an event.
- **Per-channel retry attempts.** Only the terminal outcome (`Delivered`/`Failed`) is an event; the
  intermediate `RETRYING` transitions are internal.

## Next documents in this BC

- [api.md](api.md) — preferences CRUD, SSE endpoint, inbox/history reads
- Sequence diagram: `diagrams/sequence/event-to-notification.md`
- [domain-model.md](domain-model.md), [database.md](database.md) — already written
