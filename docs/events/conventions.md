# Event Conventions

Conventions for designing, naming, evolving, and consuming domain events in Aperitivo.

Events are **in-process** (Spring Modulith application events, persisted in `event_publication`, published after commit). No broker in the MVP — see [ADR 0008](../adr/0008-event-transport.md). These conventions are written so that externalizing to a broker later requires no contract changes.

## Naming

- **Past tense, domain language.** `WorkoutPublished`, `IntegrationRevoked`. Never `PublishWorkout` or `StravaTokenInvalidated`.
- **Aggregate-name + verb.** The first word identifies the entity, the rest the verb. `ScheduledSessionDue` (entity: ScheduledSession; verb: Due).
- **No implementation leakage.** Names describe *what happened in the domain*, not how it was implemented.

## Event identity and naming for externalization

Even though events are in-process today, each event type has a stable logical name in kebab-case with a version suffix, e.g. `workout-published.v1`, `integration-revoked.v1`. If events are ever externalized to a broker, this maps directly to a topic/queue name (e.g. `aperitivo.workout-published.v1`) with no change to the event contract.

## Versioning

- **Backward-compatible changes** (adding optional fields, broadening enums): keep the same version.
- **Breaking changes** (removing fields, narrowing types, renaming): introduce a new version. Both run in parallel until consumers migrate; then the old version is retired.
- Event contracts live alongside the publishing BC's source; CI checks backward compatibility within a version.

## Delivery semantics

- **Treat delivery as at-least-once**, even in-process. Spring Modulith retries incomplete event publications (e.g. after a crash, on restart), so a listener may observe an event more than once.
- Consumers must therefore be **idempotent**, keyed on the envelope `event_id`.
- **Per-user ordering.** Events carry `user_id`. Where ordering matters, it is enforced per user (consumer-side), not globally. We do not design any consumer that requires global ordering across users.

## Idempotency patterns for consumers

Three standard patterns; choose per consumer:

1. **Idempotent state transitions.** The logic is naturally idempotent (e.g. "set status to REVOKED" — running it twice is fine).
2. **Deduplication table.** The consumer keeps a `processed_events(event_id, processed_at)` table and checks before processing.
3. **Inbox pattern.** Inbound events are recorded in an inbox table with `event_id` as primary key; consumer logic is driven from the inbox, giving exactly-once *processing* even though delivery is at-least-once.

Default for Aperitivo: **Inbox pattern** for BCs that publish their own events as a result of consuming (Analytics, Planning); **idempotent state transitions** where possible (Notifications, Ingestion).

## Publication and the dual-write problem

A publisher must not change business state and emit an event non-atomically (the dual-write problem: one succeeds, the other fails, state and events diverge).

Spring Modulith solves this for us: the event publication is written to the `event_publication` table **in the same transaction** as the business change. If the transaction commits, the event is durably recorded and will be delivered (with retry); if it rolls back, no event exists. This is the Outbox pattern, provided by the framework rather than hand-rolled. If events are externalized later, Spring Modulith's externalization relays from this same table — still no dual write.

## Event payload design

- **Carry enough data to be useful without forcing a callback.** Consumers should not need to call back into the publisher in the common case — that recreates synchronous coupling.
- **Do not over-stuff.** Avoid unrelated data. The temptation is to send "everything about the workout" in `WorkoutPublished`; instead send key identifiers and summary metrics, and let consumers fetch streams or splits separately if needed.
- **Reference IDs are internal Aperitivo UUIDs**, not provider-specific. The primary reference is the internal `workout_id`, even if a `provider_activity_id` is also present.
- **Always include `user_id`** — for per-user ordering/idempotency now, and as the partition key if externalized later.

## What is NOT a domain event

- **Internal cache invalidations.** Use a direct in-process call or a cache library.
- **Request/response.** That's a method or API call. Domain events are one-way, fire-and-forget facts.
- **Anything that "shouldn't really have happened."** If an event would only be emitted in failure cases, reconsider — it's probably an error signal, not a domain event. (Exception: meaningful failure facts like `IngestionFailed` after retry exhaustion.)
