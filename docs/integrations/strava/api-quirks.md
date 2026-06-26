# Strava Integration: API Quirks

The practical, often-undocumented behaviours of the Strava API that Aperitivo's design has to
account for — rate limits, response headers, pagination, delivery characteristics, and the gaps
between Strava's docs and reality. This is the **field guide** to Strava's actual behaviour; our
*responses* to these quirks live in Ingestion and its
[rate-limits note](../../technical-notes/strava-rate-limits.md).

## Rate limits — the real bottleneck

Strava enforces **two simultaneous windows, per application** (not per user, not per token):

| Window | Default | Resets |
|---|---|---|
| Short | **100 requests / 15 min** | aligned to the clock quarter-hour |
| Daily | **1000 requests / day** | aligned to UTC midnight |

This — **not** JVM throughput — is the system's real bottleneck
([architecture overview](../../architecture/overview.md)). Every authenticated call draws from the
same app-wide budget: backfill pages, webhook-triggered activity fetches, reconciliation sweeps,
**and token refreshes**. A single new user's multi-year backfill can starve everyone else's
realtime ingestion if ungoverned — which is why fair-share is a correctness concern, detailed in
[strava-rate-limits.md](../../technical-notes/strava-rate-limits.md).

### Rate-limit headers (authoritative usage)

Every API response carries the current usage, which we treat as the **source of truth** (reconciling
our own Redis counters against it):

```
X-RateLimit-Limit:  100,1000      ← short,daily limits
X-RateLimit-Usage:  17,142        ← short,daily usage so far
```

Both values are **comma-separated short,daily**. After every call we overwrite our local counters
from `X-RateLimit-Usage`, so local drift (from a lost-state restart, or a call that bypassed the
limiter) self-heals.

### `429 Too Many Requests`

When exceeded, Strava returns `429`. It **may** include a `Retry-After`; if absent, back off to the
next quarter-hour boundary. In steady state we should essentially never see a `429` (admission
control prevents it); a sustained `429` rate means our accounting is miscalibrated — the trigger for
the `strava-rate-limit-exceeded` runbook.

### Overage / suspension

Sustained limit-busting can get an application's API access **throttled or suspended** by Strava —
an existential risk for a Strava-dependent product. This is the ultimate reason rate-limit
governance is non-negotiable, not a nice-to-have.

## Pagination

Activity-list endpoints (`GET /athlete/activities`) paginate:

```
GET /athlete/activities?before={epoch}&after={epoch}&page={n}&per_page={k}
```

- `per_page` default is 30, **max 200**. We use a larger page for backfill efficiency (fewer calls =
  less budget) within the 200 cap.
- `after` / `before` are **epoch seconds**, filtering by activity start time. Backfill walks forward
  with `after={cursor}`; reconciliation uses `after={watermark}`.
- Pagination ends on a **short/empty page** (fewer than `per_page` results). There is no total-count
  header — you page until Strava returns less than a full page.
- **Ordering:** results are returned **most-recent-first** by default. Backfill accounts for this
  when advancing its cursor (the `SyncCursor` tie-break on `last_synced_activity_id` handles
  same-second boundaries — see [Ingestion domain-model](../../contexts/activity-ingestion/domain-model.md)).

## The summary-vs-detail distinction

A critical Strava shape quirk that drives our two-call ingestion:

- `GET /athlete/activities` returns **summary** activity objects — no laps, no segment efforts, no
  streams, no detailed splits.
- `GET /activities/{id}` returns the **detailed** activity — laps, segment efforts, best efforts,
  the full summary, and (with `include_all_efforts`) all segment efforts.
- **Streams** (`heartrate`, `watts`, `latlng`, …) are a **separate endpoint entirely**:
  `GET /activities/{id}/streams?keys=...&key_by_type=true`.

This is exactly why ingestion **enumerates** with the list endpoint (cheap summaries → one `SyncJob`
each) and **fetches detail** per activity in the worker — the two-phase shape in the
[backfill sequence](../../contexts/activity-ingestion/diagrams/sequence/initial-backfill.md). And
it's why tier-3 streams are a distinct fetch, landing in Analytics, not Catalog.

## Webhook delivery characteristics

(Subscription setup and verification are in [webhooks.md](webhooks.md); here are the *delivery*
behaviours that shape our durability design.)

- **The 2-second rule.** Strava expects a `2xx` within **~2 seconds**; slow/failed responses are
  treated as delivery failures, and **chronic** failure can **deactivate the subscription**. This
  governs the entire receive-before-process design ([Ingestion api](../../contexts/activity-ingestion/api.md)).
- **Retries: up to 3 attempts.** If Strava doesn't get a `2xx`, it retries the delivery up to **3
  times** total, then gives up. A useful backstop — but all 3 can fall inside one outage, so it is
  **not** a substitute for our own durable inbox + reconciliation.
- **Body carries identifiers only.** The webhook payload has `object_id`, `owner_id`, `aspect_type`,
  etc. — **never** activity data. We always re-fetch with our own credentials. This means even a
  forged webhook can at most cause a wasted (rate-limited, 404-safe) `GET`.
- **At-least-once, possibly out-of-order.** Duplicates happen (hence dedup on `SyncJob`); ordering is
  not guaranteed (hence consumers tolerate out-of-order create/update/delete).
- **One subscription per application**, not per user — an app-global resource configured once per
  environment.

## The `X-Strava-Signature` uncertainty

A specific, documented-as-open quirk: Strava's **official webhook example code** reads an
`X-Strava-Signature` header (HMAC-SHA256 over `timestamp.body`), **but this header is not in the main
Webhook Events API spec**, and community reports indicate deliveries historically did **not** carry
it. The state of this header must be **confirmed against a live subscription during implementation**
([Ingestion api](../../contexts/activity-ingestion/api.md) flags this in the receive path). If
present, HMAC verification becomes the preferred cheap receipt-time guard; if absent, we rely on
`subscription_id` matching + the authenticated re-fetch as the real trust boundary. Our design does
not assume it, so adding it is strict hardening, not a contract change.

## Other documented gotchas

- **`expires_at` is an absolute epoch**, not a TTL. Compute "expired?" as `now >= expires_at`, not by
  tracking a duration.
- **Refresh token may or may not rotate.** Store whatever the refresh response returns; never assume
  it's unchanged ([oauth-flow.md](oauth-flow.md)).
- **Scopes are comma-separated** and the user can deselect them — verify granted scopes, don't assume
  ([oauth-flow.md](oauth-flow.md)).
- **Activity timestamps come in two flavours:** `start_date` (UTC) and `start_date_local` (the
  athlete's local wall-clock at the activity). Planning's matching uses `start_date_local` for
  calendar-date matching ([Planning domain-model](../../contexts/training-planning/domain-model.md)).
- **Distances/elevations are metric** (metres) in the API regardless of the athlete's display
  preference — unit conversion is a presentation concern, not a data one.
- **Deleted activities:** a `delete` webhook gives only the `object_id`; there's nothing to fetch
  (the activity is gone) — the `delete`-aspect job produces no `RawActivityPayload` and emits
  `ActivityDeleted` ([Ingestion domain-model](../../contexts/activity-ingestion/domain-model.md)).

## Related

- [strava-rate-limits.md](../../technical-notes/strava-rate-limits.md) — how we govern the budget
- [webhooks.md](webhooks.md) — subscription, verification, deauth
- [data-model-mapping.md](data-model-mapping.md) — the JSON → Workout mapping the detail call feeds
- [Activity Ingestion BC](../../contexts/activity-ingestion/README.md) — where all this is handled
- [architecture overview](../../architecture/overview.md) — Strava as the single dependency / bottleneck
