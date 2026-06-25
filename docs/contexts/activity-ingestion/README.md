# Bounded Context: Activity Ingestion

**Responsibility:** The boundary with the external world. Receives Strava webhooks, runs sync jobs, handles retries and rate limits, and produces canonical `ActivityIngested` events for downstream BCs.

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — WebhookEvent, SyncJob, RawActivityPayload, SyncState
- [Database Schema](database.md) — tables, idempotency constraint, indexes, lifecycles
- [Events](events.md) — `ActivityIngested`, `ActivityDeleted`, `IngestionFailed`; consumed `IntegrationConnected/Revoked`
- [API](api.md) — webhook receiver endpoint, internal sync triggers
- Sequence diagrams: [webhook](diagrams/sequence/activity-ingestion-webhook.md), [initial backfill](diagrams/sequence/initial-backfill.md)
- Technical note: [Strava rate limits](../../technical-notes/strava-rate-limits.md)

## Summary

The Ingestion BC is the **anti-corruption layer** between Strava and the rest of Aperitivo. It absorbs:
- Webhook delivery quirks (duplicates, drops, out-of-order)
- Strava API rate limits and quota management
- Strava-specific JSON structure (kept as `RawActivityPayload` for replay)
- Authentication failures and token revocation

Downstream BCs see only clean `ActivityIngested` events — they never know about HTTP retries, signature verification, or rate-limit budgets.

It owns four persistence concerns: `WebhookEvent` (durable inbox), `SyncJob` (unit of work), `RawActivityPayload` (verbatim archive — the replay source of truth), and `SyncState` (per-source watermark). Three job sources converge at `SyncJob`: webhook (realtime, via the inbox), initial backfill (paginated, on `IntegrationConnected`, bypasses the inbox), and periodic reconciliation (scheduled, bypasses the inbox).

## Key invariants

- **Idempotency.** A given Strava activity (`provider_activity_id`) is ingested once into the canonical model, regardless of webhook redelivery — enforced by the `(provider, provider_activity_id, aspect_type)` unique constraint on `SyncJob`.
- **No data loss.** A dropped webhook is caught by periodic reconciliation within the sync window.
- **Rate-limit compliance.** The BC never exceeds Strava's 100/15min or 1000/day quotas — see [strava-rate-limits.md](../../technical-notes/strava-rate-limits.md).
- **Durability of receipt.** A webhook is blind-inserted to the inbox and answered `200` fast, before any processing — the inbox is the durable async queue (no broker).

## Events published

- `ActivityIngested` (`activity-ingested.v1`) — create/update merged via an `aspectType` field
- `ActivityDeleted` (`activity-deleted.v1`)
- `IngestionFailed` (`ingestion-failed.v1`) — the documented exception to "failure is not an event"

## Events consumed

- `IntegrationRevoked` (from IAM) — cancel pending sync jobs for the revoked user.
- `IntegrationConnected` (from IAM) — trigger initial backfill.

## External dependencies

- Strava webhook subscription (one per environment, not per user).
- Strava REST API (per-user OAuth tokens, fetched via IAM `TokenManager`).
