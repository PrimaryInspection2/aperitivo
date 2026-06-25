# Activity Ingestion — Events

The events the Activity Ingestion Bounded Context **publishes** and **consumes**: payload contracts, emit conditions, the guarantees that hold, and how each maps onto the externalization envelope.

Conventions in force: [events/conventions.md](../../events/conventions.md), catalog: [events/catalog.md](../../events/catalog.md), transport: [ADR 0008](../../adr/0008-event-transport.md). Naming: [conventions/naming.md](../../conventions/naming.md). Source of the entities and emit points: [domain-model.md](domain-model.md).

## What Ingestion publishes

| Event | Logical name | Emitted by | When |
|---|---|---|---|
| `ActivityIngestedEvent` | `activity-ingested.v1` | `SyncJobWorker` | a `create`/`update` job fetched, stored, and normalized one activity |
| `ActivityDeletedEvent` | `activity-deleted.v1` | `SyncJobWorker` | a `delete`-aspect job was processed |
| `IngestionFailedEvent` | `ingestion-failed.v1` | `SyncJobService` | a job exhausted retries and transitioned to `DEAD` |

## What Ingestion consumes

| Event | Logical name | From | Handler action |
|---|---|---|---|
| `IntegrationConnectedEvent` | `integration-connected.v1` | IAM | `BackfillService` starts the initial history backfill for the new source |
| `IntegrationRevokedEvent` | `integration-revoked.v1` | IAM | cancel pending `SyncJob`s for the user; stop issuing Strava calls |

This is the first BC in the system that both consumes and publishes domain events — IAM only published. The consume side is covered under [Consumed events](#consumed-events) below; their contracts are owned by IAM ([identity-access/events.md](../identity-access/events.md)), not redefined here.

---

## Transport recap

All published events are Java `record`s emitted via `ApplicationEventPublisher` from inside the emitting service's `@Transactional` method. Spring Modulith writes the publication to the `event_publication` table **in the same transaction** as the business change (Outbox), and delivers it **after commit** to `@ApplicationModuleListener` consumers in other BCs. Roll back, no event; commit, the event is durably recorded and retried until each listener completes.

Delivery is therefore **at-least-once**. Consumer idempotency is discussed under [Guarantees](#guarantees-and-emit-conditions).

Events are plain records — they do **not** extend `org.springframework.context.ApplicationEvent` (see naming conventions).

> **Note on the ingest→publish boundary.** `ActivityIngestedEvent` is published from `SyncJobWorker` after the payload is stored — i.e. the worker's transaction commits both the `RawActivityPayloadEntity` row (and the `sync_jobs` status flip to `COMPLETED`) **and** the event-publication row together. The actual Strava `GET` happens *before* that transaction (network I/O is not held inside a DB transaction); only the persist-and-publish is transactional. So "fetched the activity" and "published the event" are not atomic with each other — but "stored the payload" and "published the event" are. A crash after the `GET` but before the store simply leaves the job to retry; the dedup key makes the re-fetch a no-op at the job level.

## Event identity — what we carry now, and why

As in IAM, we do **not** put a synthetic `eventId` field in the records. Event identity is owned by Spring Modulith: each publication is a row in `event_publication` with its own id, stable across retries for that `(event, listener)` pair. Where a consumer needs an explicit dedup key it uses a **natural business key already in the payload** rather than a synthetic id.

For Ingestion's events that key is the activity reference. `ActivityIngestedEvent` and `ActivityDeletedEvent` both carry `providerActivityId` (plus `provider`), and the canonical internal reference where one exists. A consumer (Catalog) dedups on the activity, not on a synthetic envelope id. If events are ever externalized, identity is added **then** — relaying Modulith's publication id into a header, or adding an optional `eventId` field (a backward-compatible change, same `v1`).

`occurredAt` **is** carried — the domain time the fact happened (the ingest/delete/failure moment), assigned by the emitting service at event construction inside the transaction, distinct from the publication/commit time Modulith records.

## The externalization envelope

The [event catalog](../../events/catalog.md) defines the common wire envelope (`event_id`, `event_type`, `event_version`, `occurred_at`, `producer`, `trace_id`, `user_id`, `data`). In-process today there is no separate envelope object: the published record carries the domain-meaningful fields, the rest is derived at the externalization boundary.

| Envelope field | Source | Notes |
|---|---|---|
| `event_id` | Spring Modulith publication id | added at externalization; **not** a record field today |
| `event_type` | record class simple name minus `Event` | `ActivityIngestedEvent` → `"ActivityIngested"` |
| `event_version` | the `.vN` suffix of the logical name | `v1` |
| `occurred_at` | `record.occurredAt` | domain time the fact happened |
| `producer` | constant `"activity-ingestion"` | the publishing module |
| `trace_id` | current OTel span context | attached at externalization |
| `user_id` | `record.userId` | always present; partition key if externalized |
| `data` | the remaining record fields | event-specific payload |

The JSON "externalized form" shown per event includes `event_id` because it is filled in at the externalization boundary, even though the in-process record does not carry it.

---

## Event contracts

### `ActivityIngestedEvent`

One activity has been fetched from Strava, stored as a `RawActivityPayload`, and normalized into a canonical, provider-agnostic pre-Workout shape ready for Catalog. This is the BC's primary output and the event the whole pipeline hangs off.

```java
public record ActivityIngestedEvent(
        UUID     userId,             // owner; partition key
        UUID     rawPayloadId,       // the stored RawActivityPayloadEntity
        Provider provider,           // STRAVA in MVP
        long     providerActivityId, // Strava activity id — natural dedup key
        String   aspectType,         // "create" | "update" — distinguishes first ingest vs edit
        Instant  occurredAt          // when ingestion completed
) {}
```

**Consumers:** Workout Catalog — normalizes the payload into a `Workout` and publishes `WorkoutCreated` (first ingest) or `WorkoutUpdated` (edit).

**`create` vs `update` in one event.** Both fetch-and-store, both emit `ActivityIngestedEvent`; `aspectType` lets Catalog decide publish-vs-update without a second event type. A `create` for an activity Catalog already has, or an `update` for one it doesn't, are both handled on Catalog's side idempotently — Ingestion does not try to second-guess Catalog's state.

**Payload rationale:** `rawPayloadId` points at the stored payload so Catalog reads the full Strava JSON from Ingestion's archive rather than receiving the (potentially large) body in the event — events stay small, the heavy data is fetched on demand. `providerActivityId` is carried as the **natural dedup key** and because it is Catalog's correlation handle to Strava. `providerUserId` is intentionally **absent**: Catalog keys everything on the internal `userId`; the Strava athlete id is not its concern.

**Externalized form:**

```json
{
  "event_id": "…",
  "event_type": "ActivityIngested",
  "event_version": "v1",
  "occurred_at": "2026-06-21T18:30:05Z",
  "producer": "activity-ingestion",
  "trace_id": "…",
  "user_id": "3a9e…",
  "data": {
    "rawPayloadId": "p77c…",
    "provider": "STRAVA",
    "providerActivityId": 14207176594,
    "aspectType": "create"
  }
}
```

---

### `ActivityDeletedEvent`

Strava told us an activity was deleted (`aspect_type=delete`). No payload is fetched — there is nothing to fetch — so this event carries only the reference needed to retract the activity downstream.

```java
public record ActivityDeletedEvent(
        UUID     userId,
        Provider provider,
        long     providerActivityId, // the activity to retract — natural dedup key
        Instant  occurredAt
) {}
```

**Consumers:** Workout Catalog — deletes (or tombstones) the corresponding `Workout` and publishes `WorkoutDeleted`, which Analytics and Planning consume to back out derived state.

**Payload rationale:** no `rawPayloadId` — a delete produces no payload row (invariant). `providerActivityId` is the handle Catalog uses to find the `Workout` to retract. Minimal by design: deletion needs identity and nothing else.

**Externalized form:**

```json
{
  "event_id": "…",
  "event_type": "ActivityDeleted",
  "event_version": "v1",
  "occurred_at": "2026-06-21T18:32:00Z",
  "producer": "activity-ingestion",
  "trace_id": "…",
  "user_id": "3a9e…",
  "data": {
    "provider": "STRAVA",
    "providerActivityId": 14207176594
  }
}
```

---

### `IngestionFailedEvent`

A `SyncJob` exhausted its retries and went `DEAD` — we could not ingest this activity despite backoff. This is a **meaningful failure fact**, not an error log line: a downstream consumer (Notifications) acts on it. It is the documented exception to "failure cases are not domain events" (see conventions).

```java
public record IngestionFailedEvent(
        UUID     userId,
        Provider provider,
        long     providerActivityId,
        String   aspectType,
        String   reason,             // terminal failure summary (safe, non-sensitive)
        Instant  occurredAt
) {}
```

**Consumers:** Notifications — may tell the user "we couldn't sync one of your activities; it'll be retried on the next reconciliation." (Reconciliation will independently re-discover the activity later, so a `DEAD` job is not necessarily permanent data loss — it's "this attempt-chain gave up.")

**Payload rationale:** `reason` is a short, safe summary (e.g. `"strava_5xx_after_retries"`, `"activity_not_found"`), never a stack trace or token — the same discipline as the API error bodies ([api-errors.md](../../conventions/api-errors.md)). Full diagnostics live in logs/traces correlated by `trace_id`, not in the event. `aspectType` is carried so a consumer can phrase a delete-failure differently from a fetch-failure.

**Dedup key (if a consumer needs one):** `(providerActivityId, aspectType)` — one job, one terminal failure.

**Externalized form:**

```json
{
  "event_id": "…",
  "event_type": "IngestionFailed",
  "event_version": "v1",
  "occurred_at": "2026-06-21T19:05:00Z",
  "producer": "activity-ingestion",
  "trace_id": "…",
  "user_id": "3a9e…",
  "data": {
    "provider": "STRAVA",
    "providerActivityId": 14207176594,
    "aspectType": "create",
    "reason": "strava_5xx_after_retries"
  }
}
```

---

## Consumed events

Ingestion subscribes to two IAM events. Their contracts are owned by IAM ([identity-access/events.md](../identity-access/events.md)); summarized here only for the handler behavior and idempotency.

### `IntegrationConnectedEvent` → start backfill

`BackfillService.onIntegrationConnected` runs on every `ACTIVE` transition (first connect or reconnect after revoke). It decides **full backfill vs incremental catch-up** from the presence/absence of a `SyncCursor` for `(userId, provider)`:
- **No cursor** (first connect) → paginate the athlete's full history, enqueuing a `SyncJob` (source `BACKFILL`) per activity.
- **Cursor present** (reconnect) → enqueue only the gap since `lastSyncedAt` — equivalent to an immediate reconciliation pass.

This first-vs-reconnect decision is **Ingestion's**, read from its own sync-state — it is deliberately *not* encoded in the IAM event (which is identical in both cases by design). 

**Idempotency:** every enqueued job carries the `(provider, providerActivityId, aspectType)` key, so a redelivered `IntegrationConnectedEvent` re-runs backfill but creates no duplicate jobs — the pagination re-discovers the same activities and the unique constraint absorbs them. Backfill is thus safe to replay.

### `IntegrationRevokedEvent` → stop the user's sync

`onIntegrationRevoked` cancels the user's pending/runnable `SyncJob`s (transition `PENDING`/`FAILED` → a cancelled terminal state) and ensures no further Strava calls are issued for that user.

**Idempotency:** naturally idempotent — cancelling already-cancelled jobs is a no-op. No dedup key needed. A redelivered revocation changes nothing on the second pass.

> Note the asymmetry with deauth-as-webhook: a Strava deauthorization arrives as a **webhook** (handled by `WebhookController` → IAM `markRevoked`), and the resulting `IntegrationRevokedEvent` is what Ingestion consumes here. Ingestion does not handle the raw deauth webhook's revocation logic — it only reacts to the IAM event after IAM has marked the source `REVOKED`. The webhook *receipt* is Ingestion's (it owns the endpoint); the *revocation decision* is IAM's.

---

## Guarantees and emit conditions

1. **`ActivityIngestedEvent` fires once per successful `create`/`update` job.** The job's idempotency key guarantees one successful job per `(activity, aspect)`, hence one event. A redelivered webhook or a reconciliation overlap does not produce a second event — the duplicate job is absorbed before fetch.
2. **`ActivityDeletedEvent` fires once per `delete` job**, on the same idempotency guarantee.
3. **`IngestionFailedEvent` fires only on the `→ DEAD` transition**, once per exhausted job. A job that later succeeds via reconciliation is a *new* job (different attempt-chain) and does not retroactively un-emit the failure.
4. **Transactional publication.** All three are written to `event_publication` in the same transaction as the state change they describe (payload store + status flip, or status flip to `DEAD`). No dual write.
5. **At-least-once delivery; idempotent consumers.** Catalog dedups on `(provider, providerActivityId)`; Notifications dedups `IngestionFailed` on `(providerActivityId, aspectType)`. No synthetic event id required in MVP.
6. **Per-user ordering only where it matters.** Events carry `userId`. Catalog does not require global ordering; for a single activity, `create` precedes `update` precedes `delete` naturally because each is a distinct job gated by Strava's own event order — and Catalog handles out-of-order arrival idempotently regardless (an `update` for an unknown activity, or a `delete` before a `create`, are tolerated).

## What is deliberately NOT an event here

Per the "what is NOT a domain event" rule in conventions:

- **Webhook receipt.** Accepting a webhook and writing the `WebhookEvent` row is an internal inbox mechanic, not a domain fact for other BCs. Downstream cares about *ingested activities*, not *received webhooks*.
- **`SyncJob` lifecycle transitions** (`PENDING`→`IN_PROGRESS`→`COMPLETED`, retries). Internal work-tracking, observed via metrics/traces. Only the terminal `DEAD` transition is a fact worth broadcasting (`IngestionFailed`); intermediate retries are not.
- **Cursor advancement / reconciliation runs.** Operational sweeps, not domain facts. A reconciliation that finds nothing emits nothing; one that finds a missed activity emits the ordinary `ActivityIngestedEvent`, indistinguishable from a webhook-driven one (by design — downstream must not care which source ingested an activity).
- **Rate-limit budget changes / 429 backoffs.** Pure ingestion-internal governance, surfaced via metrics (the `strava-rate-limit-exceeded` runbook), never as a domain event.
- **A single backfill page completing.** Backfill emits one `ActivityIngestedEvent` per activity, not a "page done" or "backfill complete" event in MVP. (Backfill *progress* for the user's "we're syncing your history" UI is surfaced via SSE from Notifications, not via a domain event — see [sse-streaming.md](../../technical-notes/sse-streaming.md).)

## Next documents in this BC

- [api.md](api.md) — webhook receiver endpoint, internal sync triggers
- Sequence diagrams: `diagrams/sequence/activity-ingestion-webhook.md`, `initial-backfill.md`
- [domain-model.md](domain-model.md) — already written
- [database.md](database.md) — already written
