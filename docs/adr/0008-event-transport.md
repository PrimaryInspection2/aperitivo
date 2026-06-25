# ADR 0008: Event transport — Spring Modulith local events, no Kafka in MVP

- **Status:** Accepted
- **Date:** 2026-06-14

## Context

Aperitivo is event-driven: Bounded Contexts communicate through domain events (`ActivityIngested` → `WorkoutCreated` → `PersonalRecordSet`/`SessionCompleted` → notifications). The earlier documentation implicitly assumed Apache Kafka as the event transport, but the choice was never explicitly justified in an ADR.

This ADR makes the decision explicit.

The question: do we need an external message broker (Kafka) for inter-BC events, or do Spring Modulith's in-process events suffice?

Key facts shaping the decision:

- Aperitivo is a **modular monolith** — all six BCs run in a single JVM. There are no cross-process consumers in the MVP, Phase 2, or Phase 3 roadmap.
- The **source of truth is entity tables** (`Workout`, `ConnectedSource`, `Plan`, …), not an event log. Recomputing a new metric means iterating over `workouts`, not replaying `WorkoutCreated` events. Event-sourcing / log replay is not a requirement.
- The real throughput bottleneck is **Strava's API rate limits** (100 requests / 15 min, 1000 / day per app), not JVM event throughput. At 100K users this is on the order of single-digit events per second — trivially within reach of in-process handling.
- Spring Modulith provides **persisted, transactional in-process events** out of the box via the `event_publication` table, plus a native migration path to externalized brokers.

## Decision

**Use Spring Modulith local (in-process) events for all inter-BC communication in the MVP. Do not add Kafka or any external broker to the stack.**

Concretely:

- Domain events are published via Spring's `ApplicationEventPublisher`.
- Spring Modulith persists each publication in the `event_publication` table, in the **same transaction** as the originating business change — giving durability and transactional publication without a broker.
- Consumers use `@ApplicationModuleListener` (which implies `@Async` + `@Transactional` + `@TransactionalEventListener(phase = AFTER_COMMIT)` semantics), so they run after the publisher's transaction commits.
- Consumer idempotency is handled with an Inbox-style pattern keyed on a natural business key in the payload (no synthetic eventId in MVP — see [event conventions](../events/conventions.md)), for consumers that publish their own events as a result (Analytics, Planning).
- Module boundaries are verified at build time with `ApplicationModules.verify()`.

**The events are designed to be Kafka-ready without using Kafka:**

- Past-tense domain naming (`WorkoutCreated`, not `CreateWorkout`).
- Explicit versioning of event contracts.
- `user_id` carried in every event payload — the natural partition key if events are ever externalized.
- The event catalog documents events as **contracts**, not as Java class signatures.

The `@Externalized` annotation is **not** applied, and no broker runs in Docker Compose.

## Alternatives considered

### Apache Kafka (with or without Spring Modulith `@Externalized`)

What it is: an external, durable, partitioned log. Events published to topics, consumed by independent consumer groups with their own offsets.

What it uniquely offers over Spring Modulith local events:
- Cross-process / cross-language consumers
- Multiple independent consumer groups per event
- Long-term replay from a retained log

Why rejected for MVP: **none of those three capabilities is needed.** All BCs are in one JVM (no cross-process consumers). No event has competing independent consumer groups. Source of truth is entity tables, not the event log (no replay need). In exchange, Kafka would add: an extra broker (plus KRaft/Zookeeper) to run and monitor, consumer-lag dashboards, schema management, Testcontainers overhead on every integration test, and meaningful cognitive load — all during a learning-focused, single-developer phase, deployed to a single VPS.

### Plain Spring `@EventListener` (no Spring Modulith)

In-memory only: events are lost if the application crashes between publish and handling, and publication is not transactional by default. Rejected — Spring Modulith's persisted `event_publication` table gives durability and transactional publication for essentially the same programming model.

### RabbitMQ / NATS / Redis Streams

Same fundamental trade-off as Kafka (external broker, operational overhead) without Kafka's specific strengths. No reason to prefer them here, and the same "not needed in-process" argument applies. Rejected.

## Consequences

**Positive:**
- One fewer infrastructure component to run, monitor, back up, and upgrade.
- Durability and transactional publication still guaranteed via the `event_publication` table.
- Simpler local development and testing (no broker Testcontainer).
- The event-driven programming model is fully exercised — Outbox, Inbox, idempotency, partitioning concerns — with fewer moving parts, which is good for the learning goal.
- A principled "we don't need Kafka, here's why" is a stronger staff-level signal than including Kafka as cargo-cult.

**Negative:**
- No out-of-the-box cross-process fan-out. If a second process ever needs these events, work is required to externalize them (mitigated below).
- No long-term event log for audit/forensics (mitigated: source of truth is the entity tables plus structured logs/traces).

**Risks:**
- A future requirement genuinely needs a broker. Mitigation: Spring Modulith is explicitly designed so that local events can be externalized by adding `@Externalized` and broker configuration, **without rewriting publishers or consumers**. The Kafka-ready event design above preserves this path.

**Triggers to revisit (add Kafka when any becomes real):**
- A cross-process or cross-language consumer of our events appears.
- We expose public webhooks (events delivered to external systems).
- We build a data-warehouse / analytics pipeline (e.g. Snowflake, BigQuery) fed by events.
- A global, non-`user_id`-partitioned consumer is needed (e.g. centralized anomaly detection).

None of these is present in the Phase 1–3 roadmap.

## References

- [ADR 0005: Bounded Context decomposition](0005-bounded-contexts.md)
- [Event conventions](../events/conventions.md)
- [Event catalog](../events/catalog.md)
- [Spring Modulith reference — Working with Application Events](https://docs.spring.io/spring-modulith/reference/events.html)
