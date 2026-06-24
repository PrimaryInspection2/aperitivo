# Technical Note: Strava Rate-Limit Governance

How Activity Ingestion stays inside Strava's API quota — the real bottleneck of the system
(not JVM throughput). Complements the [Activity Ingestion BC](../contexts/activity-ingestion/domain-model.md),
which references this note for the mechanics behind "the worker respects 429 with backoff".

## The limits we govern against

Strava enforces **two simultaneous windows**, per application (not per user):

| Window | Default limit | Resets |
|---|---|---|
| Short | **100 requests / 15 min** | aligned to the clock quarter-hour (`:00, :15, :30, :45`) |
| Daily | **1000 requests / day** | aligned to UTC midnight |

Both are application-wide. Every authenticated call — backfill page, webhook-triggered
`GET /activities/{id}`, reconciliation sweep, token refresh — draws from the **same** shared
budget. This is the single most important operational fact about the BC: a backfill of one
new user's 5-year history can starve every other user's realtime webhook processing if it is
not governed. Fair-share is therefore not a nicety; it is correctness.

> The limits are also **returned on every response** via headers — `X-RateLimit-Limit:
> 100,1000` and `X-RateLimit-Usage: 17,142` (short,daily). We treat these as the source of
> truth and reconcile our local counters against them (below), rather than trusting our own
> count blindly — Strava's count is authoritative and accounts for calls we may not have
> tracked (e.g. a redeploy that lost in-flight state).

## Where the budget lives — Redis counters, not Postgres

Budget state is **ephemeral, high-churn, and never transactional with activity data**, so it
does not belong in Postgres ([database.md](../contexts/activity-ingestion/database.md) records
this explicitly as "no rate-limit-budget table"). It lives in **Redis**:

```
ratelimit:strava:short   → INCR, key TTL to the next quarter-hour boundary
ratelimit:strava:daily   → INCR, key TTL to the next UTC midnight
```

Counter pattern (atomic, no read-modify-write race):

```
local n = redis.call('INCR', KEYS[1])
if n == 1 then redis.call('EXPIRE', KEYS[1], window_seconds) end
return n
```

Wrapped in a small Lua script so increment-and-set-expiry is atomic. A request is **admitted**
only if both windows have headroom; otherwise the caller backs off (below). On every Strava
response, the actual `X-RateLimit-Usage` values overwrite the counters (`SET`, preserving TTL),
so local drift self-heals each call.

Why Redis and not an in-process counter: the budget is **application-wide across all worker
threads/instances**. An in-JVM `AtomicLong` would be correct only for a single instance; the
moment there is a second instance (or even a planned restart mid-window), the shared external
counter is the only correct home. Single-instance MVP could use in-process, but Redis is
already in the stack and makes the design horizontally honest at zero extra cost.

## Admission: a token-bucket gate in front of every Strava call

All outbound Strava traffic funnels through one component — call it `StravaRateLimiter` —
sitting in front of `StravaActivityClient`. Nothing calls Strava without passing it.

```
boolean tryAcquire()       // non-blocking: true if both windows have headroom
Duration acquireOrDelay()  // returns 0 if admitted, else the Duration until the next reset
```

`SyncJobWorker` and `BackfillService` consult it before each call:

- **Headroom in both windows** → proceed.
- **Short window exhausted** → the job is not failed; its `next_attempt_at` is pushed to the
  next quarter-hour boundary and it returns to `PENDING`. The worker moves to other jobs.
- **Daily window exhausted** → push to the next UTC midnight. (Rare; mostly a giant backfill.)

The key design choice: **rate-limit deferral is rescheduling, not failure.** A deferred job
never increments its retry/attempt count and never moves toward `DEAD` — exhausting the budget
is an expected steady state, not an error. This keeps `IngestionFailedEvent` meaning "this
activity could not be ingested", never "we were busy".

## Reacting to a 429 we didn't predict

Local counters can lag (clock skew, lost state across a restart, a call we didn't route
through the limiter by mistake). So even with admission control, a `429 Too Many Requests` can
come back. Handling:

1. Read `Retry-After` if present; otherwise compute the delay to the next short-window boundary.
2. **Hard-set** the local short counter to its limit (we now know we are at the ceiling).
3. Reschedule the job (`next_attempt_at = now + delay`, status back to `PENDING`) — again, **no
   retry-count increment**, this is governance not failure.
4. Emit a metric (`strava_rate_limit_429_total`) — a *sustained* 429 rate means our admission
   control is miscalibrated and is the trigger for the `strava-rate-limit-exceeded` runbook.

A 429 is the backstop; admission control is the primary mechanism. In steady state we should
essentially never see a 429 — seeing them regularly is a bug in our accounting, not normal.

## Fair-share: backfill must not starve realtime

The hardest case: a new user connects and their backfill wants to make hundreds of calls,
while existing users' webhooks need a handful of calls *now* for a good realtime experience.

MVP policy — **priority by source, simple and sufficient:**

- The worker's claim query orders runnable jobs so that `source = WEBHOOK` and
  `source = RECONCILIATION` outrank `source = BACKFILL`. Realtime activity ingestion and
  gap-filling win the budget; backfill consumes only the **leftover** headroom.
- Backfill is additionally **throttled to a soft ceiling** of the short window (e.g. never
  consume more than ~70 of the 100 short-window slots), leaving guaranteed headroom for
  webhooks even during a large backfill. The ceiling is config, not hardcoded.

This is deliberately not a sophisticated weighted-fair-queue. At MVP scale (single app
subscription, modest user count) priority-by-source plus a backfill ceiling is enough. A true
per-user fair-share scheduler is a documented future step if multi-tenant load ever justifies
it — it is **not** MVP.

> Note the interaction with token refresh: `POST /oauth/token` calls **also** count against the
> same quota. At our refresh rate (single-digit/sec even at 100K users, per
> [ADR 0003](../adr/0003-token-storage-strategy.md)) this is negligible, but the limiter counts
> them too — refresh is not exempt.

## What this buys, restated as invariants

- The BC never knowingly exceeds 100/15min or 1000/day (admission control), and self-corrects
  if it ever does (429 backstop + counter reconciliation from response headers).
- Budget exhaustion defers work, never fails it — `IngestionFailed` stays semantically clean.
- A large backfill cannot starve realtime webhook processing — priority-by-source + backfill
  ceiling guarantee webhook headroom.
- All of this is **ingestion-internal** — no other BC, and no domain event, ever learns about
  rate-limit budgets ([events.md](../contexts/activity-ingestion/events.md): "rate-limit budget
  changes … never as a domain event").

## Observability

- Gauges: `strava_ratelimit_short_used` / `_daily_used` (from response headers, authoritative).
- Counters: `strava_ratelimit_429_total`, `strava_ratelimit_deferred_total{source}`.
- Alert: sustained 429s, or short-window usage pinned at the ceiling for multiple consecutive
  windows with a growing `PENDING` backlog → the `strava-rate-limit-exceeded` runbook.

## Future steps (not MVP)

- **Per-user weighted fair-share** if multi-tenant load makes priority-by-source too coarse.
- **Proactive budget-aware backfill pacing** (spread a large backfill over hours by design
  rather than greedily consuming leftover headroom).
- **Multiple Strava apps / subscription sharding** if the single-app quota ever becomes a hard
  ceiling for the whole platform — a significant architectural step, well beyond MVP.

## Related

- [Activity Ingestion — domain model](../contexts/activity-ingestion/domain-model.md)
- [Activity Ingestion — database](../contexts/activity-ingestion/database.md) (why no budget table)
- [Activity Ingestion — webhook sequence](../contexts/activity-ingestion/diagrams/sequence/activity-ingestion-webhook.md) (the 429 branch in the worker hot path)
- [ADR 0003: Token storage strategy](../adr/0003-token-storage-strategy.md)
