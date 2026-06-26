# Runbook: Database Connection Saturation

**Alert:** Postgres connection-pool utilization near max.

## Symptom

Requests slow or fail to acquire a DB connection; latency climbs across all BCs (they share one
Postgres). In the worst case, the webhook receive path slows — risking the 2-second Strava budget.

## Why it matters

All six BC schemas live in one Postgres instance ([deployment.md](../deployment.md)). Connection
exhaustion is a system-wide symptom, not one BC's problem. And it can cascade into **webhook
deactivation** if receipt breaches 2 seconds ([Ingestion api](../../contexts/activity-ingestion/api.md)
documents this exact scenario).

## Diagnose

1. **Who's holding connections?** Long-running queries? A backfill doing many small writes? The
   hypertable bulk insert under load?
2. **Pool size vs load:** is the pool simply undersized for current concurrency, or is something
   **leaking** connections (not returning them)?
3. **Slow queries:** check for missing-index scans or a query regression — the per-BC indexes are
   designed for the hot paths; a query not hitting one saturates connections by running long.

## Mitigate

- **Immediate:** the webhook receive path is *designed* to decouple from DB pressure — it does one blind
  insert and responds 200, deferring resolution ([Ingestion api](../../contexts/activity-ingestion/api.md)).
  Confirm receipt is still fast even under saturation; if it isn't, that decoupling has regressed and is
  the priority fix.
- **Connection leak:** restart to reclaim; then find the unclosed connection path.
- **Undersized pool:** raise the pool size if the host can take it; or shed load (pause backfill — it's
  the most connection-hungry, lowest-priority work).

## Resolve

- Once the leak/slow-query/sizing is fixed, utilization returns to baseline. Backfill resumes
  automatically (it's just deferred work).

## Prevent

- Keep backfill's write pattern efficient (batch where possible).
- Watch for query regressions against the documented per-BC indexes.
- Ensure the receive-before-process decoupling holds — it's the firewall between DB pressure and the
  Strava 2-second budget.
