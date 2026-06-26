# Runbook: Strava Rate Limit Exceeded

**Alert:** sustained `429`s from Strava, or `strava_ratelimit_short_used` pinned at the ceiling for
multiple consecutive windows with a growing `sync_jobs{status=PENDING}` backlog.

Governance design: [strava-rate-limits.md](../../technical-notes/strava-rate-limits.md).

## Symptom

Ingestion is slow or stalled; the Strava-budget Grafana panel shows short-window usage at 100/100 (or
daily at 1000/1000); `strava_ratelimit_429_total` is climbing.

## Why it matters

The Strava budget is the system's real bottleneck. Sustained busting can get the **application's API
access throttled or suspended by Strava** — an existential risk ([api-quirks.md](../../integrations/strava/api-quirks.md)).

## Diagnose

1. **Which window?** Short (100/15min) or daily (1000/day) — the panel shows both.
2. **What's consuming it?** Check `strava_ratelimit_deferred_total{source}` — is it `BACKFILL`
   (a new user's large history) or `WEBHOOK`/`RECONCILIATION` (steady load growth)?
3. **Is admission control working?** In steady state 429s should be ~zero. A nonzero 429 rate means
   local counters drifted from `X-RateLimit-Usage` — a calculation/accounting bug, not just load.

## Mitigate (now)

- If it's a **backfill** flooding the budget: the priority-by-source + backfill ceiling should already
  be protecting webhooks. Confirm webhooks are still flowing (realtime ingestion lag normal). If the
  ceiling is mis-set, lower the backfill soft-ceiling config so backfill yields more headroom.
- If **429s persist despite headroom showing locally**: the counters are wrong. Force a reconcile of
  the Redis counters from the last `X-RateLimit-Usage` response; the next call self-heals them.
- Deferred jobs are **rescheduled, not failed** — no `IngestionFailed` should fire from this. Confirm
  the backlog is `PENDING` (waiting), not `DEAD` (given up).

## Resolve

- The windows reset on the clock quarter-hour / UTC midnight; the PENDING backlog drains automatically
  once headroom returns. No manual intervention needed if governance is working — the system is
  *designed* to sit at the ceiling and defer.

## Prevent

- If this recurs under normal (non-backfill) load, the user base has outgrown the single-app budget —
  the trigger to consider the per-user fair-share scheduler or multiple Strava apps
  ([strava-rate-limits.md](../../technical-notes/strava-rate-limits.md), future steps).
- Verify admission control's counter reconciliation is firing on every response.
