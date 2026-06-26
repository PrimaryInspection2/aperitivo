# Runbook: Ingestion Lag

**Alert:** `ingestion_lag` (activity `event_time` → ingested) above threshold for N minutes.

## Symptom

Activities created on Strava are taking too long to appear in Aperitivo. Users notice their workout
isn't showing up promptly.

## Diagnose

1. **Is it rate-limit-driven?** Check the Strava-budget panel — if usage is at the ceiling, this is
   downstream of [strava-rate-limit-exceeded](strava-rate-limit-exceeded.md); go there first.
2. **Are webhooks arriving?** Check `webhook_received_total` rate. A flat line means Strava stopped
   delivering — possible subscription deactivation (chronic 2-second-rule failures) or a Strava-side
   issue.
3. **Is the worker draining jobs?** Check `sync_jobs{status=PENDING}` vs `IN_PROGRESS`. A large PENDING
   pool with little IN_PROGRESS means the worker is stalled, not the budget.
4. **Is processing erroring?** Check job FAILED/DEAD rates and the worker's trace spans for the Strava
   `GET`.

## Mitigate

- **Subscription deactivated** (no webhooks): reconciliation is the safety net — it will catch missed
  activities within its sweep interval, so data isn't lost, only delayed. Re-establish the webhook
  subscription (see [webhooks.md](../../integrations/strava/webhooks.md)); confirm the validation
  handshake succeeds.
- **Worker stalled:** check for a stuck `IN_PROGRESS` job holding a claim (the `@Version` claim guard
  should prevent permanent locks, but a hung Strava call could stall a worker thread). Restart the app
  if a thread is wedged; in-flight jobs are idempotent and safe to reprocess.
- **Budget-driven:** follow the rate-limit runbook.

## Resolve

- Once webhooks resume or the worker drains, lag returns to normal. Reconciliation guarantees
  eventual completeness regardless — webhooks are the latency optimization, reconciliation is the
  correctness guarantee ([api-quirks.md](../../integrations/strava/api-quirks.md)).

## Prevent

- Monitor the subscription's health (webhook receipt rate) as a leading indicator.
- Ensure the 2-second receive budget is never breached (receive-before-process design) — a slow
  receive path is the most common cause of subscription deactivation.
