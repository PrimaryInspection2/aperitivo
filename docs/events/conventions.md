# Event Conventions

Conventions for designing, naming, evolving, and consuming domain events in Aperitivo.

Events are **in-process** (Spring Modulith application events, persisted in `event_publication`, published after commit). No broker in the MVP — see [ADR 0008](../adr/0008-event-transport.md). These conventions are written so that externalizing to a broker later requires no contract changes.

## Naming

- **Past tense, domain language.** `WorkoutCreated`, `IntegrationRevoked`. Never `CreateWorkout` or `StravaTokenInvalidated`.
- **Aggregate-name + verb.** The first word identifies the entity, the rest the verb. `ScheduledSessionDue` (entity: ScheduledSession; verb: Due).
- **No implementation leakage.** Names describe *what happened in the domain*, not how it was implemented.

## Logical name ↔ Java type

Each event contract has **two** surface forms that refer to the same thing:

- a **logical name** in kebab-case with a version suffix — `workout-created.v1` — used in the [event catalog](catalog.md), in diagrams, and as the future broker topic name;
- a **Java record type** with an `Event` suffix — `WorkoutCreatedEvent` — used in code and in the per-BC `events.md` payload definitions.

The catalog lists logical names; the BC `events.md` files show the records. They are the same contract in two registers; do not treat a difference in casing/suffix as two events.

## Event identity and naming for externalization

Even though events are in-process today, each event type has a stable logical name in kebab-case with a version suffix, e.g. `workout-created.v1`, `integration-revoked.v1`. If events are ever externalized to a broker, this maps directly to a topic/queue name (e.g. `aperitivo.workout-created.v1`) with no change to the event contract.

MVP records do **not** carry a synthetic `eventId` field — event identity is owned by Spring Modulith's `event_publication` row, and consumers dedup on a natural business key already in the payload. A payload-carried `eventId` is added only if/when events are externalized (a backward-compatible new optional field). See each BC's `events.md` for the per-event dedup key.

## Versioning

- **Backward-compatible changes** (adding optional fields, broadening enums): keep the same version.
- **Breaking changes** (removing fields, narrowing types, renaming): introduce a new version. Both run in parallel until consumers migrate; then the old version is retired.
- Event contracts live alongside the publishing BC's source; CI checks backward compatibility within a version.

## Delivery semantics

- **Treat delivery as at-least-once**, even in-process. Spring Modulith retries incomplete event publications (e.g. after a crash, on restart), so a listener may observe an event more than once.
- Consumers must therefore be **idempotent**, keyed on a natural business key in the payload (the envelope `event_id` is the externalization-time equivalent, not present on the in-process record in MVP).
- **Per-user ordering.** Events carry `user_id`. Where ordering matters, it is enforced per user (consumer-side), not globally. We do not design any consumer that requires global ordering across users.

## Idempotency patterns for consumers

Three standard patterns; choose per consumer:

1. **Idempotent state transitions.** The logic is naturally idempotent (e.g. "set status to REVOKED" — running it twice is fine).
2. **Deduplication table.** The consumer keeps a `processed_events(business_key, processed_at)` table and checks before processing.
3. **Inbox pattern.** Inbound events are recorded in an inbox table keyed by the business key; consumer logic is driven from the inbox, giving exactly-once *processing* even though delivery is at-least-once.

Default for Aperitivo: **Inbox pattern** for BCs that publish their own events as a result of consuming (Analytics, Planning); **idempotent state transitions** where possible (Notifications, Ingestion). The dedup key is the natural business key documented in each BC's `events.md` (e.g. `(provider, providerActivityId)` for activity-derived events), not a synthetic id in MVP.
