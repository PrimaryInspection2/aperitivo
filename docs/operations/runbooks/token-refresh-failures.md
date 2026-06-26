# Runbook: Token Refresh Failures

**Alert:** spike in `token_refresh_failures_total` across many users.

Mechanism: [token-management.md](../../technical-notes/token-management.md).

## Symptom

Many users' Strava token refreshes are failing at once. If unaddressed, their `ConnectedSource`s go
`ERROR`/`REVOKED` and their ingestion stops.

## Diagnose

1. **One user or many?** A single user failing is normal (they deauthorized) — handled automatically
   (mark REVOKED, emit `IntegrationRevoked`). A **spike across many** is the alert condition and points
   at something systemic.
2. **What's the error?** Check the refresh span / logs:
   - `400 invalid_grant` across many users → unlikely to be coincidental real revocations; suspect a
     **clock skew** bug (computing `expired?` wrong) or a **Strava-side** issue.
   - Timeouts / 5xx from `/oauth/token` → Strava is down or throttling refreshes (refreshes count
     against the rate-limit budget — check the budget panel).
3. **Clock check:** is the host clock correct? `expires_at` is an absolute epoch; a skewed clock makes
   the app think tokens are expired (or valid) wrongly ([api-quirks.md](../../integrations/strava/api-quirks.md)).

## Mitigate

- **Clock skew:** fix host time (NTP). The per-user lock + re-read-under-lock means a corrected clock
  immediately stops the spurious refresh storm.
- **Strava-side outage:** refreshes are lazy and retry on next use; existing access tokens (~6h life)
  keep working until expiry, so there's a buffer. Don't mass-revoke — wait for Strava to recover.
- **Rate-limit-driven refresh failures:** follow the rate-limit runbook; refreshes are deferred like
  any call.

## Resolve

- Real revocations resolve themselves (user reconnects → fresh `ConnectedSource`). Systemic causes
  (clock, Strava outage) resolve when the root cause is fixed; no per-user action needed.

## Prevent

- Alert on host clock drift directly.
- Consider proactive refresh (background refresh of soon-to-expire tokens) to surface failures earlier
  and off the webhook hot path — a documented optional optimization
  ([token-management.md](../../technical-notes/token-management.md)).
