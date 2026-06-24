# Sequence: Activity Ingestion — Initial Backfill

How a newly connected Strava source gets its **full activity history** pulled in, starting
from the `IntegrationConnectedEvent` IAM publishes when a source becomes `ACTIVE`.

Backfill is one of the three sources of ingestion work. Unlike the webhook channel, it
**bypasses the `WebhookEvent` inbox** — there is no Strava push to record, so no inbox row
is fabricated. It enqueues `SyncJob`s directly and they converge on the **same worker hot
path** as webhook-driven jobs (drawn in [activity-ingestion-webhook.md](activity-ingestion-webhook.md));
that half is referenced here, not duplicated.

Contracts and locked decisions: [domain-model.md](../../domain-model.md),
[events.md](../../events.md), [api.md](../../api.md), [database.md](../../database.md).

## What this diagram is built to show

1. **The trigger is an in-process domain event**, consumed `AFTER_COMMIT` of IAM's source
   activation — `@ApplicationModuleListener` on `IntegrationConnectedEvent`. Backfill never
   runs synchronously inside the OAuth callback.
2. **Pagination is a `loop`** over `GET /athlete/activities?after={cursor}&page=N`, rate-limit
   aware, enqueuing one `SyncJob(source=BACKFILL)` per activity summary on each page.
3. **The cursor (`sync_state`) is read at the start and advanced as pages complete**, so a
   crash mid-backfill resumes from the last reliably-synced point rather than re-paging from
   zero; `@Version` serializes advancement against a racing reconciliation/webhook update.
4. **Convergence, not duplication.** Each enqueued job is picked up by the shared
   `SyncJobWorker` path; the per-activity `GET` + store + `ActivityIngestedEvent` is the same
   TX-3 region as the webhook flow and is referenced, not re-drawn.

## Diagram

```mermaid
sequenceDiagram
    autonumber
    participant IAM as IAM<br/>(IntegrationConnectedEvent)
    participant BF as BackfillService
    participant SS as sync_state
    participant TM as TokenManager<br/>(IAM)
    participant SC as StravaActivityClient
    participant SJS as SyncJobService
    participant SJR as sync_jobs
    participant WK as SyncJobWorker

    IAM-->>BF: @ApplicationModuleListener<br/>onIntegrationConnected(event)  %% AFTER_COMMIT of source ACTIVE
    Note right of BF: in-process, after IAM commits.<br/>No WebhookEvent — backfill is not a Strava push.

    BF->>SS: read SyncCursor for (userId, provider)
    alt no cursor yet (brand-new source)
        SS-->>BF: null → "sync from the beginning"
    else cursor present
        SS-->>BF: last_synced_at / last_synced_activity_id
        Note right of BF: incremental backfill from the watermark
    end

    Note over BF,SJR: PAGINATION — rate-limit-aware, page by page
    loop until Strava returns an empty / short page
        BF->>TM: getValidAccessToken(userId)
        TM-->>BF: access token
        BF->>SC: GET /athlete/activities?after={cursor}&page=N&per_page=K
        alt 200 OK with activities
            SC-->>BF: [activity summaries]
            loop per activity summary on this page
                BF->>SJS: enqueue(provider, providerActivityId, aspectType=create, userId, source=BACKFILL)
                alt new (provider, activityId, aspect)
                    SJS->>SJR: INSERT SyncJob(status=PENDING)
                else duplicate — uq_sync_jobs_idempotency
                    SJR-->>SJS: violation (caught) → no-op
                    Note right of SJR: webhook may have already enqueued<br/>this activity — collapsed here, harmless
                end
            end
            BF->>SS: advance cursor to newest activity on page<br/>(last_synced_at, last_synced_activity_id, @Version)
        else 429 rate limited
            SC-->>BF: 429 + Retry-After
            Note right of BF: pause per Retry-After, resume same page;<br/>backfill is throttled, never abandoned
        else empty / short page
            SC-->>BF: [] (no more)
            Note right of BF: pagination complete
        end
    end

    BF->>SS: stamp last_reconciled_at (backfill counts as a full sweep)

    Note over WK,SJR: SHARED HOT PATH — identical to the webhook flow's TX-3
    WK->>SJR: claim PENDING jobs (source=BACKFILL, same as WEBHOOK)
    Note over WK: per job: TokenManager → GET /activities/{id} →<br/>store RawActivityPayload + publish ActivityIngestedEvent.<br/>See activity-ingestion-webhook.md (TX-3) — not re-drawn here.
```

## Notes keyed to the locked decisions

- **Two-phase shape on purpose.** `BackfillService` does the *enumeration* (paginate the
  history, enqueue jobs, advance the cursor). The *per-activity fetch + normalize + publish*
  is the `SyncJobWorker`'s job — the same code path that serves webhook and reconciliation
  jobs. Backfill does not have its own private fetch path; convergence at `SyncJob` is the
  whole point of the three-source design.
- **Why pagination enqueues summaries rather than fetching detail inline.** Keeping
  enumeration and detail-fetch separate means the rate-limit budget is governed in *one*
  place (the worker), and a backfill of thousands of activities is naturally chunked into
  retryable `SyncJob`s instead of one long fragile loop. A crash leaves committed jobs to be
  drained by the worker; only the un-paged tail needs re-enumeration, resumed from the cursor.
- **Cursor advances per page, not per activity.** `last_synced_at` plus the
  `last_synced_activity_id` tie-break let `?after=` resume without skipping or re-including a
  boundary activity that shares a timestamp second. `@Version` serializes a backfill cursor
  write against a concurrent webhook/reconciliation write on the same `sync_state` row.
- **Backfill emits no "backfill started/complete" domain event.** Each activity surfaces as an
  ordinary `ActivityIngestedEvent`, indistinguishable downstream from a webhook-driven one
  (by design — Catalog must not care which source ingested an activity). User-facing
  "we're syncing your history" progress is an SSE concern from Notifications, not a domain
  event — see `sse-streaming.md`.
- **Overlap with webhooks is expected and safe.** A webhook for a fresh activity can arrive
  while backfill is still paging older history; both enqueue the same `(provider, activityId,
  create)` and the second is collapsed at `uq_sync_jobs_idempotency`. No coordination needed
  between the two sources beyond that one constraint.
