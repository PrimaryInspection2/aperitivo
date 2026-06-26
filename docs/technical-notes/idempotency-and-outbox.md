# Technical Note: Idempotency and Outbox Patterns

How Aperitivo gets **exactly-once *processing*** out of **at-least-once *delivery*** — without a
message broker. This note collects the idempotency mechanisms that are stated per-BC across the
docs into one reference: the publish-side Outbox, the three consume-side dedup patterns the system
actually uses, and the registry of dedup keys.

Complements [ADR 0008](../adr/0008-event-transport.md) (in-process events) and
[spring-modulith-boundaries.md](spring-modulith-boundaries.md) (the `@ApplicationModuleListener`
mechanics). Conventions: [events/conventions.md](../events/conventions.md).

## The core problem

Events are delivered **at-least-once**, even in-process: Spring Modulith persists each publication
in `event_publication` and **retries incomplete ones** (after a crash, on restart), so a listener
can observe the same event more than once. Two consequences the whole system is designed around:

1. **Publishing must not lose an event** if the business transaction commits — solved by the
   **Outbox**.
2. **Consuming must be idempotent** — solved by one of **three dedup patterns**, chosen per
   consumer.

No synthetic `eventId` is carried on the in-process record in MVP; dedup keys are **natural
business keys** in the payload ([conventions.md](../events/conventions.md)).

## Publish side — the Outbox (`event_publication`)

The hazard at publish time is the **dual-write problem**: write business state to the DB *and*
publish an event, where one can succeed and the other fail (e.g. commit the row, crash before the
broker ack — the event is lost; or publish, then roll back — a phantom event). A broker makes this
acute; we avoid it entirely with the Outbox.

**Mechanism.** Spring Modulith writes the event into the `event_publication` table **in the same
transaction** as the business state change. So:

```
@Transactional
  write business rows (e.g. INSERT raw_activity_payload)
  publish DomainEvent          ──▶ Modulith writes an event_publication row in THIS tx
commit                          ──▶ business rows + outbox row commit atomically
                                    (or both roll back — never one without the other)

AFTER_COMMIT
  Modulith delivers to @ApplicationModuleListener consumers (async)
  on success, the publication row is marked complete
  incomplete rows are retried (startup republish) — hence at-least-once
```

This is the single source of the phrase repeated in every BC's events doc: *"published in the same
transaction as the state change — no dual write."* Concretely in the system:

- **Ingestion** writes `raw_activity_payload` + publishes `ActivityIngested` in one tx — the
  diagram's "TX-3: stored and published are atomic" ([webhook sequence](../contexts/activity-ingestion/diagrams/sequence/activity-ingestion-webhook.md)).
- **Catalog** persists the `Workout` aggregate + publishes `WorkoutCreated` in one tx.
- **Analytics** writes the PR row + publishes `PersonalRecordSet` in one tx.
- **Notifications** flips `DeliveryAttempt` status + publishes `NotificationDelivered/Failed` in one tx.

> Note the deliberately-documented **non-atomic** seam in Ingestion: the Strava `GET` happens
> *before* TX-3 and is **not** in it. "Stored↔published" is atomic; "fetched↔published" is not. A
> crash between fetch and store just retries the job, and the consume-side dedup (below) makes the
> re-fetch a no-op. This is the one place the system is explicit that only *part* of a flow is
> transactional — and why consumer idempotency is non-negotiable.

## Consume side — three dedup patterns

The system does **not** use one universal dedup mechanism; it uses three, chosen by what the
consumer does. This is intentional and worth understanding, because picking the wrong one is either
unsafe (missed dedup) or over-engineered (a table where a constraint suffices).

### Pattern 1 — Idempotent state transition (no dedup storage)

When the handler's effect is **naturally idempotent**, no dedup machinery is needed — running it
twice yields the same state.

- **Used by:** Ingestion's `IntegrationRevoked` handler ("cancel this user's pending jobs" —
  cancelling already-cancelled jobs is a no-op). IAM's revoke transition ("set status REVOKED").
- **Why it's enough:** the operation is a set-to-target, not an increment or an append. Redelivery
  changes nothing on the second pass.
- **Cost:** zero — no table, no constraint.

### Pattern 2 — Unique-constraint dedup (the DB rejects the duplicate)

When processing **creates a row**, put a **unique constraint on the natural business key** and let
the database reject the duplicate; catch the violation and treat it as a no-op. This is the
workhorse pattern.

- **Used by:**
  - **Ingestion** — `uq_sync_jobs_idempotency (provider, provider_activity_id, aspect_type)`. A
    redelivered webhook, or a webhook/reconciliation overlap, tries to create a `SyncJob` that
    already exists → rejected **before** the expensive Strava `GET`, saving rate-limit budget.
  - **Catalog** — `uq_workouts_provider_activity (provider, provider_activity_id)`. A redelivered
    `ActivityIngested` upserts the same workout, never a duplicate.
  - **Analytics** — `delete-by-activity + reinsert` scoped to `(provider, provider_activity_id)`
    for samples; `uq_dtl_user_day (user_id, day)` upsert for training load. Re-delivery
    re-materializes the same activity, no double-count.
  - **Notifications** — `uq_notifications_dedup (dedup_key)`. A redelivered upstream event computes
    the same `dedup_key`; the insert is rejected → the whole render/deliver pipeline is skipped.
- **Why a constraint, not a check-then-insert:** the constraint is **atomic** and race-free; a
  "SELECT then INSERT" has a window where two threads both see "absent" and both insert. Let the DB
  arbitrate.
- **Cost:** one unique index per consumer; the dedup key is a column already needed.

### Pattern 3 — Inbox (drive processing from a recorded inbox row)

When a consumer **publishes its own events as a result of consuming** — so a duplicate would cascade
downstream — record inbound events in an inbox keyed by the business key, and drive the handler from
the inbox so processing is exactly-once even though delivery is at-least-once.

- **Default for:** Analytics and Planning (both consume `WorkoutCreated/Updated/Deleted` and emit
  their own `PersonalRecordSet` / `Session*` events — a double-process would double-emit).
- **In practice here:** the line between Pattern 2 and Pattern 3 is thin in this system, because the
  unique constraint on the *outcome* (a PR row, a match row) already prevents the duplicate
  downstream emit. The Inbox is the heavier option, used when the outcome isn't a single
  constraint-guarded row. MVP leans on Pattern 2 wherever the outcome is one guarded row, and treats
  a dedicated `processed_events`-style inbox as the fallback for multi-step consumers that need it.

> The conventions doc states the default assignment: **Inbox** for consumers that publish-on-consume
> (Analytics, Planning); **idempotent transitions** where possible (Notifications, Ingestion). This
> note refines that: in MVP, **the unique-constraint pattern covers most cases**, with a true Inbox
> reserved for a consumer whose processing isn't reducible to one constraint-guarded write.

## Why dedup on natural keys, not a synthetic event id

The MVP records carry **no synthetic `eventId`** — identity is a natural business key in the
payload. Reasons:

- The natural key is **meaningful and shared by both sides**: a redelivered webhook *and* a
  reconciliation overlap both compute `(provider, providerActivityId, aspectType)`, so dedup
  collapses duplicates from *different sources*, not just literal redeliveries. A synthetic
  per-publication id would only catch the latter.
- It avoids a column whose only job is dedup, when a column that already carries business meaning
  does the job.
- It is **broker-ready anyway**: the externalization envelope ([catalog.md](../events/catalog.md))
  adds an `event_id` *then*, as a backward-compatible field, if events ever leave the process. The
  in-process record doesn't need it.

## Dedup-key registry

The natural key per event, gathered from the per-BC `events.md` files (those remain authoritative):

| Event | Consumer | Dedup key |
|---|---|---|
| `ActivityIngested` | Catalog | `(provider, providerActivityId)` (+ `aspectType` to distinguish create/update) |
| `ActivityDeleted` | Catalog | `(provider, providerActivityId)` |
| `WorkoutCreated/Updated` | Analytics, Planning | `(provider, providerActivityId)` (+ aspect where create/update differ) |
| `WorkoutDeleted` | Analytics, Planning | `(provider, providerActivityId)` |
| `PersonalRecordSet` | Notifications | `pr:{userId}:{sportType}:{recordType}:{providerActivityId}` |
| `IntegrationRevoked` | Ingestion, Notifications | Ingestion: idempotent transition (no key). Notifications: `revoked:{connectedSourceId}` |
| `IngestionFailed` | Notifications | `ingestfail:{provider}:{providerActivityId}` |
| `ScheduledSessionDue` | Notifications | `sessiondue:{scheduledSessionId}` |
| `SessionCompleted` | Notifications | `sessiondone:{scheduledSessionId}:{workoutId}` |
| `SessionMissed` | Notifications | `sessionmiss:{scheduledSessionId}` |

Internal dedup keys (within a producer, not on an event):
- **Ingestion `SyncJob`:** `(provider, provider_activity_id, aspect_type)` — the pre-fetch guard.
- **Ingestion `RawActivityPayload`:** `(provider, provider_activity_id)` — the archive's "already
  have it?" guard.

## Ordering — and why we mostly don't need it

At-least-once is paired with **no global ordering guarantee**. Every event carries `user_id`, and
the system is designed so that **no consumer requires global ordering across users**. Where
*per-activity* order matters (`create` → `update` → `delete`), it arises naturally — each is a
distinct job/event gated by Strava's own event order — and consumers are additionally written to
tolerate out-of-order arrival idempotently:

- Catalog/Analytics/Planning handle an `update` for an unknown activity as a create, and a `delete`
  before a create as a harmless no-op.
- Notifications treats every event independently — a `SessionCompleted` before its
  `ScheduledSessionDue` is simply two separate notifications.

So ordering is a **non-requirement** by design, not a guarantee we rely on — which keeps the door
open to externalizing onto a partitioned broker later (partition by `user_id`) with no consumer
rewrite.

## Checklist for a new consumer

When adding a handler for an event:

1. **Is the effect naturally idempotent?** (set-to-target, not append/increment) → Pattern 1, done.
2. **Does it create a row?** → Pattern 2: unique constraint on the natural key, catch the violation.
3. **Does it publish its own event as a result, with an outcome that isn't one guarded row?** →
   Pattern 3: Inbox keyed on the natural key.
4. **Pick the dedup key from the producer's `events.md`** — don't invent one; reuse the natural key
   so cross-source duplicates also collapse.
5. **Never assume ordering** beyond per-user, per-activity natural order; tolerate out-of-order.

## What is deliberately NOT done

- **No synthetic event id in MVP.** Natural keys only (added at externalization, if ever).
- **No distributed-transaction / 2PC across modules.** The Outbox + idempotent consumers replace it.
- **No global event ordering.** Per-user is the only ordering the design permits itself to need.
- **No broker.** The `event_publication` Outbox is the durable log; a broker is a future,
  contract-compatible swap ([ADR 0008](../adr/0008-event-transport.md)).

## Related

- [ADR 0008: Event transport](../adr/0008-event-transport.md) — in-process events, no broker
- [Spring Modulith boundaries](spring-modulith-boundaries.md) — `@ApplicationModuleListener`, the registry
- [Event conventions](../events/conventions.md) — delivery semantics, the three patterns in brief
- [Event catalog](../events/catalog.md) — the externalization envelope (where `event_id` appears)
