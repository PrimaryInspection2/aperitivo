# Bounded Context: Notifications

**Responsibility:** Pure downstream consumer. Translates domain events from other BCs into user-facing messages across channels.

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — Notification, Channel, Template, Preference, DeliveryAttempt, the SSE registry
- [Database Schema](database.md) — preferences, templates, inbox/dedup, delivery attempts
- [Events](events.md) — the five consumed upstreams (+ dedup keys); published `NotificationDelivered/Failed`
- [API](api.md) — preferences CRUD, SSE endpoint, inbox/history, mark-read
- [Sequence: event to notification](diagrams/sequence/event-to-notification.md)
- [SSE streaming](../../technical-notes/sse-streaming.md) — the SSE mechanics

## Summary

Notifications is the **pure downstream** BC — it consumes events from everywhere else and publishes very little back (only its own delivery-status events).

Architectural shape:
- One **subscription matrix** per user: `(eventType, channel) → enabled?` (absence falls back to a code-defined default).
- One **template** per `(eventType, channel)` combination.
- An incoming domain event → dedup on its natural business key → gate on preferences → render template per channel → enqueue `DeliveryAttempt`s → deliver via channel-specific adapters.

This BC is the natural home for the **SSE endpoint** (in-app real-time push) — see [ADR 0007](../../adr/0007-sse-not-websocket.md).

> Style: entities are plain data; delivery orchestration, template rendering, and event handling live in services.

## Channels (MVP)

- **EMAIL** — transactional email via SMTP.
- **IN_APP** — SSE stream to the authenticated user's open connection, if any. Persisted as an in-app inbox when there is no live connection.
- **PUSH** — *deferred*. Requires a mobile app or web-push setup.

## SSE delivery (MVP shape)

- One long-lived `GET /api/sse/events` connection per authenticated client (JWT-authenticated).
- An in-memory `Map<UserId, List<SseEmitter>>` holds active connections (single-instance MVP).
- On a relevant event, the matching user's emitters receive the message.
- On disconnect, the emitter is evicted; the browser's `EventSource` reconnects with `Last-Event-ID`.
- Missed messages while disconnected are recovered from the in-app inbox (not a replayed stream).
- Behind nginx, the SSE response sets `X-Accel-Buffering: no` to disable buffering.
- Multi-instance scaling (sticky sessions or Redis pub/sub fan-out) is deferred — see the SSE streaming technical note.

## Key invariants

- Idempotency: receiving the same upstream event twice does not produce two notifications. Inbox pattern keyed by the upstream event's natural business key (e.g. `(provider, providerActivityId)` for activity-derived events; `(userId, sportType, recordType, providerActivityId)` for `PersonalRecordSet`) — no synthetic eventId in MVP, per the event conventions.
- User preference is respected. If a user disabled `PersonalRecordSet` for `EMAIL`, no email is sent regardless of the upstream event.
- No message lost to a dropped SSE connection: every notification persists as an inbox row first; SSE is a live-delivery optimization on top.
- Failed deliveries retry with backoff up to N attempts, then emit `NotificationFailed`.

## Events consumed

- `PersonalRecordSet` (from Analytics)
- `IntegrationRevoked` (from IAM)
- `ScheduledSessionDue`, `SessionCompleted`, `SessionMissed` (from Planning)
- `IngestionFailed` (from Ingestion)

## Events published

- `NotificationDelivered`
- `NotificationFailed`

(Both are emitted for observability and a future alerting seam; no MVP consumer.)
