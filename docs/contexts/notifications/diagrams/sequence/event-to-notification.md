# Sequence: Notifications — Event to Notification

How an inbound domain event becomes user-facing messages: dedup on the natural business key, gate
on preferences, render templates, and deliver per channel (live SSE + email), emitting
delivery-status events. Uses `PersonalRecordSet` as the worked example; every consumed event
follows the same pipeline ([events.md](../../events.md)).

Contracts: [domain-model.md](../../domain-model.md), [database.md](../../database.md),
[events.md](../../events.md). SSE mechanics: [sse-streaming.md](../../../../technical-notes/sse-streaming.md).

## What this diagram shows

1. **Idempotency first** — the dedup insert is the gate; a redelivered event stops here.
2. **Preference-gated fan-out** — one notification, one `DeliveryAttempt` per *enabled* channel.
3. **Two channel adapters** — SSE (live, in-memory registry) and email (SMTP, retryable).
4. **Inbox as durable fallback** — the notification row exists regardless of whether a live SSE
   connection is present; a disconnected user recovers it via REST.
5. **Delivery-status events** — `NotificationDelivered`/`Failed` emitted per attempt outcome.

## Diagram

```mermaid
sequenceDiagram
    autonumber
    participant ANA as Analytics<br/>(event_publication)
    participant ND as NotificationDispatchService
    participant DB as notifications schema
    participant PREF as PreferenceService
    participant DEL as DeliveryService
    participant SSE as SseRegistry<br/>(Map&lt;UserId,List&lt;SseEmitter&gt;&gt;)
    participant SMTP as EmailChannelAdapter
    participant EP as event_publication<br/>(Notifications outbox)

    ANA-->>ND: @ApplicationModuleListener PersonalRecordSetEvent<br/>{userId, sportType, recordType, value, previousValue, providerActivityId}
    Note over ND: AFTER_COMMIT of Analytics' write — at-least-once

    Note over ND,DB: DEDUP — the idempotency gate
    rect rgb(238, 248, 238)
        ND->>ND: dedupKey = "pr:{userId}:{sportType}:{recordType}:{providerActivityId}"
        ND->>DB: INSERT notification(dedup_key, …)
        alt unique violation (already processed)
            DB-->>ND: uq_notifications_dedup violation (caught)
            Note over ND: redelivery → idempotent no-op, STOP
        else inserted (first time)
            DB-->>ND: notification row created (the inbox row)
        end
    end

    Note over ND,PREF: GATE + RENDER per channel
    ND->>PREF: effectivePreferences(userId, PERSONAL_RECORD)
    PREF-->>ND: {EMAIL: enabled(default on), IN_APP: enabled(default on)}
    loop per enabled channel
        ND->>ND: render template(PERSONAL_RECORD, channel, params)
        ND->>DB: INSERT delivery_attempt(notification_id, channel, status=QUEUED)
    end

    Note over DEL,SMTP: DELIVER — worker drains QUEUED attempts
    DEL->>DB: claim runnable delivery_attempts (QUEUED / RETRYING past backoff)

    par IN_APP channel
        DEL->>SSE: send(userId, event=PERSONAL_RECORD, id=dedupKey, data)
        alt user has a live emitter
            SSE-->>DEL: delivered
            DEL->>DB: attempt status=SENT
            DEL->>EP: publish NotificationDeliveredEvent{channel=IN_APP}
        else no live connection
            Note over SSE: nothing to push live —<br/>the inbox row already persists it;<br/>client fetches it on reconnect via REST
            DEL->>DB: attempt status=SENT (inbox is the delivery)
            DEL->>EP: publish NotificationDeliveredEvent{channel=IN_APP}
        end
    and EMAIL channel
        DEL->>SMTP: send(rendered subject + body)
        alt SMTP accepted
            SMTP-->>DEL: 250 OK
            DEL->>DB: attempt status=SENT
            DEL->>EP: publish NotificationDeliveredEvent{channel=EMAIL}
        else transient failure
            SMTP-->>DEL: 4xx / timeout
            DEL->>DB: attempt status=RETRYING, attempt_count++, next_attempt_at=backoff
            Note over DEL: re-claimed when backoff elapses
        else retries exhausted
            DEL->>DB: attempt status=FAILED
            DEL->>EP: publish NotificationFailedEvent{channel=EMAIL, reason}
        end
    end

    EP-->>EP: AFTER_COMMIT delivery of status events (observability seam; no MVP consumer)
```

## Notes keyed to the locked decisions

- **Dedup is the first thing, not the last.** The `uq_notifications_dedup` insert is what makes the
  whole pipeline idempotent under at-least-once delivery — a redelivered `PersonalRecordSet`
  collapses before any rendering or delivery work ([database.md](../../database.md)).
- **The inbox row is the source of truth, SSE is the optimization.** Whether or not the user has a
  live `SseEmitter`, the `NotificationEntity` persists; a disconnected user loses nothing and
  recovers via `GET /api/notifications`. This is the MVP's answer to "missed messages" — inbox, not
  stream replay ([sse-streaming.md](../../../../technical-notes/sse-streaming.md)).
- **One pipeline, every event.** Only the first step (extract `userId`/`dedupKey`/`params`) is
  event-specific; `IntegrationRevoked`, `IngestionFailed`, and the three Planning events all
  converge here ([events.md](../../events.md)).
- **Preference gate before any `DeliveryAttempt`.** A disabled `(eventType, channel)` produces no
  attempt at all — the user's opt-out is honored before delivery work, not after.
- **Delivery-status events have no MVP consumer.** They are published anyway (Outbox, after commit)
  as the observability/alerting seam, so adding a consumer later needs no producer change
  ([events.md](../../events.md)).
```
