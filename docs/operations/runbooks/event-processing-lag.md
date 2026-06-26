# Runbook: Event Processing Lag

**Alert:** `event_publication` incomplete-rows gauge growing — Spring Modulith event listeners falling
behind. The in-process analogue of consumer lag.

Mechanism: [idempotency-and-outbox.md](../../technical-notes/idempotency-and-outbox.md),
[spring-modulith-boundaries.md](../../technical-notes/spring-modulith-boundaries.md).

## Symptom

Downstream effects of events are delayed: a workout is ingested but its Catalog `Workout` / Analytics
metrics / Planning match / Notification lag behind. The `event_publication` table accumulates
incomplete (undelivered) rows.

## Why it happens

Events are in-process, delivered `AFTER_COMMIT` via `@ApplicationModuleListener` (async). If a listener
is slow, throwing, or the async executor is saturated, publications don't complete and pile up.

## Diagnose

1. **Which listener?** Trace the slow/failing event chain — Tempo shows the `@ApplicationModuleListener`
   spans. Identify which consumer (Catalog normalization? Analytics materialization? Planning match?) is
   lagging.
2. **Slow or failing?** A slow listener (e.g. Analytics hypertable bulk insert under load) lags but
   completes. A **throwing** listener leaves the publication incomplete and Modulith retries it — check
   for an exception loop in logs.
3. **Executor saturation?** If many events fan in at once (a large backfill emitting thousands of
   `ActivityIngested`), the async pool may be the bottleneck.

## Mitigate

- **Throwing listener:** fix or guard the exception. Because delivery is at-least-once and consumers are
  idempotent, the retried events reprocess safely once the bug is fixed — no data loss, the incomplete
  rows drain.
- **Slow listener under backfill load:** this is expected transient lag during a big backfill; it drains
  as the burst subsides. If chronic, tune the async executor pool size.
- **Stuck publication:** Modulith republishes incomplete `event_publication` rows on restart — a restart
  re-drives them (idempotent consumers make this safe).

## Resolve

- Once the slow/throwing listener is addressed, incomplete rows drain to zero. The
  `event_publication` gauge returning to baseline confirms recovery.

## Prevent

- Keep listeners fast and non-blocking; heavy work (hypertable inserts, recompute) should be efficient,
  not synchronous-slow.
- Monitor the incomplete-rows gauge as a leading indicator of any consumer health problem — it's the
  single best signal that the event backbone is healthy.
