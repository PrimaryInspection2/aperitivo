# Notifications — Domain Model

The domain model for the Notifications Bounded Context: the data it owns, the services that turn
inbound domain events into user-facing messages, the events it emits, and the invariants that hold.

Style: Spring layered ([ADR 0005](../../adr/0005-bounded-contexts.md)) — entities are plain data,
services hold all logic. Naming: [conventions/naming.md](../../conventions/naming.md).

## Responsibility recap

Notifications is the system's **pure downstream consumer**. It subscribes to domain events from
every other BC and turns each into zero or more user-facing messages, delivered across channels
(email, in-app/SSE), respecting per-user preferences. It publishes only its own delivery-status
events. It owns no business concept of its own beyond "a message was sent to a user"; everything
it reacts to originates elsewhere.

The shape, end to end:

```
inbound domain event
  → resolve target user
  → look up that user's enabled (eventType, channel) subscriptions
  → render the matching template per channel
  → enqueue a DeliveryAttempt per channel
  → channel adapter delivers (SMTP / SSE)
  → on success: NotificationDelivered ; on terminal failure: NotificationFailed
```

Two design commitments frame everything below:

1. **Idempotency on the inbound event's natural business key** — events are at-least-once, so the
   same upstream event must never produce two notifications.
2. **Preference-gated** — a message is only produced for a `(user, eventType, channel)` the user
   has enabled.

## What Notifications owns

| Entity | Purpose |
|---|---|
| `NotificationPreferenceEntity` | the per-user subscription matrix: `(eventType, channel) → enabled` |
| `NotificationTemplateEntity` | the message body template per `(eventType, channel)` |
| `NotificationEntity` | one rendered, user-facing message (the in-app inbox row + delivery anchor) |
| `DeliveryAttemptEntity` | one attempt to deliver one `Notification` over one channel, with retry state |

Plus one **non-persistent** runtime structure — the SSE emitter registry
(`Map<UserId, List<SseEmitter>>`) — which is in-memory, not a table (see
[sse-streaming.md](../../technical-notes/sse-streaming.md)).

### NotificationPreferenceEntity — the subscription matrix

```java
@Entity
@Table(name = "notification_preferences")
public class NotificationPreferenceEntity {
    @Id
    private UUID id;

    private UUID userId;                  // id-ref to IAM, no FK

    @Enumerated(EnumType.STRING)
    private NotificationType eventType;   // PERSONAL_RECORD, INTEGRATION_REVOKED, SESSION_DUE, …

    @Enumerated(EnumType.STRING)
    private Channel channel;              // EMAIL, IN_APP

    private boolean enabled;

    @Version
    private long version;
}
```

One row per `(userId, eventType, channel)`. Absence of a row falls back to a code-defined default
(below) — a user who never touched settings still gets sensible notifications. `enabled=false` is
an explicit opt-out.

### NotificationTemplateEntity — rendering

```java
@Entity
@Table(name = "notification_templates")
public class NotificationTemplateEntity {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    private NotificationType eventType;

    @Enumerated(EnumType.STRING)
    private Channel channel;

    private String subjectTemplate;      // for EMAIL; null for IN_APP
    private String bodyTemplate;         // parameterized — e.g. "New {recordType} PR: {value}"

    @Version
    private long version;
}
```

One template per `(eventType, channel)`. Templates are seeded data (a migration), editable later
via an admin path (post-MVP). Rendering substitutes named parameters carried on the inbound event
(e.g. `recordType`, `value`, `previousValue` from `PersonalRecordSet`).

### NotificationEntity — the message and in-app inbox row

```java
@Entity
@Table(name = "notifications")
public class NotificationEntity {
    @Id
    private UUID id;

    private UUID userId;

    @Enumerated(EnumType.STRING)
    private NotificationType eventType;

    private String dedupKey;             // the inbound event's natural business key (see below)

    private String title;                // rendered
    private String body;                 // rendered

    private boolean readByUser;          // for the in-app inbox unread count

    @CreationTimestamp
    private Instant createdAt;
}
```

A `NotificationEntity` is created once per inbound event (post-dedup) and is the **in-app inbox
row** — what the client fetches over REST on (re)connect to recover messages missed while the SSE
stream was down ([sse-streaming.md](../../technical-notes/sse-streaming.md)). `dedupKey` carries
the upstream event's natural business key and is **unique**, which is the idempotency mechanism.

### DeliveryAttemptEntity — per-channel delivery with retry

```java
@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttemptEntity {
    @Id
    private UUID id;

    private UUID notificationId;         // id-ref to NotificationEntity (same BC — could be FK)

    @Enumerated(EnumType.STRING)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    private DeliveryStatus status;       // QUEUED → SENT | FAILED | RETRYING

    private int attemptCount;
    private Instant nextAttemptAt;       // backoff schedule
    private String lastError;

    @Version
    private long version;
}
```

One per `(notification, channel)` the user has enabled. EMAIL attempts go through an SMTP adapter
with backoff retries; IN_APP attempts push to the SSE registry (and are inherently "delivered" the
moment they land in the inbox, so IN_APP rarely fails). On terminal failure (attempts exhausted),
the BC emits `NotificationFailed`.

> `notificationId` is the one place an FK is reasonable — it is **intra-BC** (both tables are
> Notifications-owned), so unlike cross-BC references it can be a real foreign key. The project
> rule is "no FK *across* BC boundaries"; within a BC, FKs are fine.

## Enums and value objects

```java
public enum Channel { EMAIL, IN_APP }          // PUSH deferred (needs mobile/web-push)

public enum DeliveryStatus { QUEUED, SENT, FAILED, RETRYING }

public enum NotificationType {
    PERSONAL_RECORD,        // ← PersonalRecordSet (Analytics)
    INTEGRATION_REVOKED,    // ← IntegrationRevoked (IAM)
    INGESTION_FAILED,       // ← IngestionFailed (Ingestion)
    SESSION_DUE,            // ← ScheduledSessionDue (Planning)
    SESSION_COMPLETED,      // ← SessionCompleted (Planning)
    SESSION_MISSED          // ← SessionMissed (Planning)
}
```

`NotificationType` is Notifications' **own** vocabulary — a stable internal taxonomy that inbound
event types map onto. This indirection means a new upstream event becomes a new
`NotificationType` + template + default, without leaking the producer BC's event-class names into
the preference matrix the user sees.

## Services

| Service | Responsibility |
|---|---|
| `NotificationDispatchService` | the inbound event handlers (`@ApplicationModuleListener`): dedup, resolve user, gate on preferences, render, enqueue `DeliveryAttempt`s |
| `DeliveryService` | drives `DeliveryAttempt`s through channel adapters; backoff retries; emits `NotificationDelivered`/`NotificationFailed` |
| `EmailChannelAdapter` | SMTP delivery |
| `SseChannelAdapter` | pushes to the in-memory emitter registry; the IN_APP delivery path |
| `SseRegistry` | holds `Map<UserId, List<SseEmitter>>`; register/evict/send (see sse-streaming.md) |
| `NotificationQueryService` | read side — the in-app inbox (unread items), delivery history, preferences |
| `PreferenceService` | CRUD on the subscription matrix |

`NotificationDispatchService` is where the consume-heavy nature lives — one listener per inbound
event type, all funneling into a common dedup→gate→render→enqueue pipeline.

## Default subscriptions

A user who never visits settings still gets the right notifications, via code-defined defaults:

| NotificationType | EMAIL default | IN_APP default |
|---|---|---|
| `PERSONAL_RECORD` | on | on |
| `INTEGRATION_REVOKED` | on | on |
| `INGESTION_FAILED` | off | on |
| `SESSION_DUE` | off | on |
| `SESSION_COMPLETED` | off | on |
| `SESSION_MISSED` | off | on |

Rationale: anything that needs the user to *act* (broken Strava connection) defaults to email;
routine in-app feedback (a session completed) defaults to in-app only, to avoid email fatigue.
`INTEGRATION_REVOKED` is the one consumed from IAM that genuinely needs attention, so it is the
strongest default.

## Persistence policy — Hibernate use in Notifications

Modest, like IAM — no rich aggregates, plain CRUD:

| Feature | Used? | Where |
|---|---|---|
| Plain JPA entities, Spring Data repositories | ✅ | all four tables |
| `@Version` optimistic locking | ✅ | `DeliveryAttempt` (retry races), preferences |
| Intra-BC FK (`delivery_attempts.notification_id`) | ✅ | both tables Notifications-owned |
| Associations **across** BC boundaries | ❌ | `userId` is a plain id-ref |
| `@OneToMany`/entity graphs/`@BatchSize` | ❌ | no aggregate structure here |

Notifications joins IAM and Analytics on the "light toolkit" side of the project's deliberate
contrast — the association playground is Catalog/Planning, not here.

## Invariants

1. **Idempotent on `dedupKey`.** One inbound event → at most one `NotificationEntity`, enforced by
   a unique constraint on `dedupKey`. A re-delivered upstream event is a no-op.
2. **Preference-gated.** No `DeliveryAttempt` is created for a `(user, eventType, channel)` that is
   disabled (or whose default is off and the user has not opted in).
3. **No message lost to a dropped SSE connection.** Every notification is persisted as an inbox row
   first; SSE is a live-delivery optimization on top, and the inbox is the durable fallback.
4. **Terminal failure is observable.** A `DeliveryAttempt` that exhausts retries emits
   `NotificationFailed`; delivery problems are never silent.
5. **No cross-BC association.** `userId` is an id-ref; Notifications never joins IAM's tables.

## Collaboration summary

```
PersonalRecordSet / IntegrationRevoked / IngestionFailed /
ScheduledSessionDue / SessionCompleted / SessionMissed
    → NotificationDispatchService (@ApplicationModuleListener, dedup on natural key)
    → PreferenceService (gate) → render template → DeliveryAttempt per channel
    → DeliveryService → EmailChannelAdapter (SMTP) / SseChannelAdapter (SseRegistry)
    → publishes NotificationDelivered / NotificationFailed
NotificationQueryService → inbox (unread), delivery history
```

## Next documents in this BC

- [database.md](database.md) — DDL, the dedup unique constraint, indexes, template seeding
- [events.md](events.md) — consumed events (all five upstreams) + published delivery-status events
- [api.md](api.md) — preferences CRUD, the SSE endpoint, inbox/history reads
- Sequence diagram: `diagrams/sequence/event-to-notification.md`
- [sse-streaming.md](../../technical-notes/sse-streaming.md) — the SSE mechanics (already written)
