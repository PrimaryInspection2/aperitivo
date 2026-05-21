# Bounded Context: Notifications

**Responsibility:** Pure downstream consumer. Translates domain events from other BCs into user-facing messages across channels.

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — *to be written* — Notification, Channel, Template, Preference, DeliveryAttempt
- [API](api.md) — *to be written* — preferences CRUD, SSE endpoint, delivery history
- [Events](events.md) — *to be written*
- [Database Schema](database.md) — *to be written*

## Summary

Notifications is the **pure downstream** BC — it consumes events from everywhere else, but publishes very little of substance back (only its own delivery status events).

Architectural shape:
- One **subscription matrix** per user: `(event_type, channel) → enabled?`.
- One **template** per `(event_type, channel)` combination.
- An incoming domain event → look up matching subscriptions → render template per channel → enqueue `DeliveryAttempt`s → process via channel-specific adapters.

This BC is the natural home for the **SSE endpoint** (in-app real-time push) — see [ADR 0007](../../adr/0007-sse-not-websocket.md).

## Channels (MVP)

- **EMAIL** — transactional email via SMTP.
- **IN_APP** — SSE stream to the authenticated user's open session, if any. Persisted as in-app inbox if no live session.
- **PUSH** — *deferred*. Requires mobile app or web push setup.

## Key invariants

- Idempotency: receiving the same upstream event twice does not produce two notifications. Inbox pattern keyed by upstream `event_id`.
- User preference is respected. If a user has disabled `PersonalRecordDetected` for `EMAIL`, no email is sent regardless of the upstream event.
- Failed deliveries retry with backoff up to N attempts, then emit `NotificationFailed`.

## Events consumed

- `PersonalRecordDetected`, `TrendDetected` (from Analytics)
- `IntegrationRevoked` (from IAM)
- `ScheduledSessionDue`, `SessionCompleted`, `SessionMissed` (from Planning)
- `IngestionFailed` (from Ingestion)

## Events published

- `NotificationDelivered`
- `NotificationFailed`
