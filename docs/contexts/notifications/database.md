# Notifications — Database Schema

Physical schema for the Notifications BC: the subscription matrix, templates, the inbox/dedup
table, and per-channel delivery attempts. Conventions: snake_case, UUID PKs, `timestamptz` UTC,
Flyway per-BC changesets. The SSE emitter registry is **in-memory**, not a table — see
[sse-streaming.md](../../technical-notes/sse-streaming.md).

## Schema ownership

Notifications owns its `notifications` schema. It reads no other BC's tables — everything arrives
as events. The only cross-BC reference is `user_id` (→ IAM `users.id`), a **plain UUID column with
no FK**, per [ADR 0005](../../adr/0005-bounded-contexts.md). The one real FK in this schema
(`delivery_attempts.notification_id`) is **intra-BC** and therefore allowed.

## Tables

### notification_preferences (subscription matrix)

```sql
CREATE TABLE notification_preferences (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL,                 -- id-ref to IAM, no FK
    event_type  TEXT NOT NULL,                 -- NotificationType enum as string
    channel     TEXT NOT NULL,                 -- EMAIL | IN_APP
    enabled     BOOLEAN NOT NULL,
    version     BIGINT NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One preference row per (user, event_type, channel).
ALTER TABLE notification_preferences
    ADD CONSTRAINT uq_pref_user_type_channel UNIQUE (user_id, event_type, channel);
```

Absence of a row = fall back to the code-defined default; a row with `enabled=false` = explicit
opt-out. The unique constraint is the access pattern (`findByUserIdAndEventTypeAndChannel`).

### notification_templates

```sql
CREATE TABLE notification_templates (
    id               UUID PRIMARY KEY,
    event_type       TEXT NOT NULL,
    channel          TEXT NOT NULL,
    subject_template TEXT NULL,                -- EMAIL only; null for IN_APP
    body_template    TEXT NOT NULL,
    version          BIGINT NOT NULL DEFAULT 0
);

ALTER TABLE notification_templates
    ADD CONSTRAINT uq_template_type_channel UNIQUE (event_type, channel);
```

Seeded by a Flyway migration (below). One template per `(event_type, channel)`.

### notifications (inbox row + dedup anchor)

```sql
CREATE TABLE notifications (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL,                -- id-ref to IAM, no FK
    event_type   TEXT NOT NULL,
    dedup_key    TEXT NOT NULL,                -- upstream event's natural business key
    title        TEXT NOT NULL,
    body         TEXT NOT NULL,
    read_by_user BOOLEAN NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- THE idempotency constraint: one notification per upstream event.
ALTER TABLE notifications
    ADD CONSTRAINT uq_notifications_dedup UNIQUE (dedup_key);

-- In-app inbox read: a user's unread items, newest first.
CREATE INDEX ix_notifications_user_unread
    ON notifications (user_id, created_at DESC)
    WHERE read_by_user = false;

-- Full inbox / history per user.
CREATE INDEX ix_notifications_user_created
    ON notifications (user_id, created_at DESC);
```

`uq_notifications_dedup` is the heart of idempotency: a re-delivered upstream event computes the
same `dedup_key` and the insert is rejected (caught and treated as a no-op). The **partial** index
on `read_by_user = false` keeps the unread-count/inbox query small regardless of total history.

> `dedup_key` composition is per upstream event, documented in [events.md](events.md): e.g.
> `pr:{userId}:{sportType}:{recordType}:{providerActivityId}` for `PersonalRecordSet`,
> `revoked:{connectedSourceId}` for `IntegrationRevoked`. It is a string so heterogeneous keys
> share one column.

### delivery_attempts

```sql
CREATE TABLE delivery_attempts (
    id              UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,  -- intra-BC FK
    channel         TEXT NOT NULL,             -- EMAIL | IN_APP
    status          TEXT NOT NULL DEFAULT 'QUEUED',  -- QUEUED|SENT|FAILED|RETRYING
    attempt_count   INT  NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NULL,          -- backoff schedule
    last_error      TEXT NULL,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One attempt row per (notification, channel).
ALTER TABLE delivery_attempts
    ADD CONSTRAINT uq_delivery_notification_channel UNIQUE (notification_id, channel);

-- The delivery worker's claim sweep: runnable attempts (QUEUED, or RETRYING past backoff).
CREATE INDEX ix_delivery_runnable
    ON delivery_attempts (status, next_attempt_at);
```

`notification_id` is a real FK with `ON DELETE CASCADE` — both tables are Notifications-owned, so
intra-BC referential integrity is appropriate (contrast the cross-BC `user_id`, which is a plain
id-ref). `ix_delivery_runnable` serves the retry worker exactly like Ingestion's
`ix_sync_jobs_runnable` serves its worker — same pattern, deliberately.

## Mapping notes (entity ↔ schema)

| Entity field | Column | Detail |
|---|---|---|
| `*.eventType: NotificationType` | `event_type TEXT` | `@Enumerated(EnumType.STRING)` |
| `*.channel: Channel` | `channel TEXT` | `@Enumerated(EnumType.STRING)` |
| `DeliveryAttemptEntity.status` | `status TEXT` | `@Enumerated(EnumType.STRING)` |
| `NotificationEntity.dedupKey` | `dedup_key TEXT` | unique; the idempotency key |
| `*.userId: UUID` | `user_id UUID` | id-ref to IAM; **no** FK |
| `DeliveryAttemptEntity.notificationId` | `notification_id UUID` | intra-BC FK, `ON DELETE CASCADE` |
| `@Version` | `version BIGINT` | optimistic locking |

Enum columns are `TEXT` (not native PG enums) so adding a `NotificationType` or `Channel` is an
application change, no `ALTER TYPE` — same forward-compat posture as the other BCs.

## Retention and growth

| Table | Growth | Retention |
|---|---|---|
| `notification_preferences` | bounded by users × event-types × channels | permanent |
| `notification_templates` | tiny, fixed (seed data) | permanent |
| `notifications` | one row per delivered event — grows with activity | prunable by age once read (e.g. keep 90 days of read inbox), unread kept |
| `delivery_attempts` | one row per (notification, channel) | prunable with its notification |

## Migrations

Flyway per-BC, under the Notifications module:
1. `V1__notifications_create_preferences.sql`
2. `V2__notifications_create_templates.sql` + **seed** the `(eventType, channel)` templates
3. `V3__notifications_create_notifications.sql` — table + dedup unique + inbox indexes
4. `V4__notifications_create_delivery_attempts.sql`

Adding a channel (`PUSH`) or a `NotificationType` needs no migration (both `TEXT`); only a new
template seed row and a default-preference entry in code.

## What is intentionally NOT here

- **No SSE-connection table.** Live emitters are in-memory (`Map<UserId, List<SseEmitter>>`); they
  are runtime state, not persistent data ([sse-streaming.md](../../technical-notes/sse-streaming.md)).
- **No cross-BC foreign keys.** `user_id` is a plain column.
- **No copy of upstream event data.** Notifications stores the rendered message, not the source
  event; it re-derives nothing from another BC's tables.
- **No per-user "last seen" cursor for SSE replay.** MVP recovers missed messages from the inbox
  (a REST read), not by replaying a precise event stream.

## Next documents in this BC

- [events.md](events.md) — consumed events + published delivery-status events; `dedup_key` recipes
- [api.md](api.md) — preferences CRUD, SSE endpoint, inbox/history
- Sequence diagram: `diagrams/sequence/event-to-notification.md`
- [domain-model.md](domain-model.md) — already written
