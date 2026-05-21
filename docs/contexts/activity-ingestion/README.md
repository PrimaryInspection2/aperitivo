# Bounded Context: Activity Ingestion

**Responsibility:** The boundary with the external world. Receives Strava webhooks, runs sync jobs, handles retries and rate limits, and produces canonical `ActivityIngested` events for downstream BCs.

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — *to be written* — WebhookEvent, SyncJob, RawActivityPayload, SyncCursor
- [API](api.md) — *to be written* — webhook receiver endpoint, internal sync triggers
- [Events](events.md) — *to be written*
- [Database Schema](database.md) — *to be written*

## Summary

The Ingestion BC is the **anti-corruption layer** between Strava and the rest of Aperitivo. It absorbs:
- Webhook delivery quirks (duplicates, drops, out-of-order)
- Strava API rate limits and quota management
- Strava-specific JSON structure (kept as `RawActivityPayload` for replay)
- Authentication failures and token revocation

Downstream BCs see only clean `ActivityIngested` events — they never know about HTTP retries, signature verification, or rate-limit budgets.

## Key invariants

- **Idempotency.** A given Strava activity (identified by `provider_activity_id`) is ingested exactly once into the canonical model, regardless of how many times a webhook is delivered.
- **No data loss.** If a webhook is dropped, periodic reconciliation will catch the missed activity within the configured sync window.
- **Rate-limit compliance.** The BC never exceeds Strava's 100/15min or 1000/day token quotas.

## Events published

- `ActivityIngested`
- `ActivityDeleted`
- `IngestionFailed`

## Events consumed

- `IntegrationRevoked` (from IAM) — cancel pending sync jobs for the revoked user.
- `IntegrationConnected` (from IAM) — trigger initial backfill.

## External dependencies

- Strava webhook subscription (one per environment, not per user).
- Strava REST API (per-user OAuth tokens, fetched via IAM `TokenManager`).
