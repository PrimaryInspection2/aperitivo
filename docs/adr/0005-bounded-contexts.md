# ADR 0005: Bounded Context decomposition for MVP

- **Status:** Accepted
- **Date:** 2026-05-20
- **Reviewed:** 2026-06-14 (event-transport phrasing updated; decomposition unchanged)

## Context

Aperitivo is a modular monolith. The decomposition into modules — Bounded Contexts in DDD terms — is the most consequential architectural decision in the project, because:

- BC boundaries dictate ownership of code, schema, and events.
- Poorly drawn boundaries lead to the "distributed monolith" anti-pattern, where modules can't change independently because they all read each other's tables.
- The decomposition is the answer to the staff-level question *"why is your architecture organized this way?"* — and the answer must be principled, not historical.

We applied the standard DDD strategic heuristics:
1. **Language** — does the same entity name mean the same thing? If not, it lives in different BCs.
2. **Invariants and rules** — who owns the business rules on this data?
3. **Cadence of change** — what changes together stays together; what changes for different reasons separates.
4. **Conway's lens** — if two independent teams owned these parts, where would the boundary minimize cross-team friction?
5. **Source of truth vs read models** — write side and read side can live in different BCs.

> Note: we adopt DDD's **strategic** patterns (bounded contexts, ubiquitous language, domain events). We deliberately do **not** adopt the tactical rich-domain-model pattern; entities are data, services hold behavior. This does not affect the decomposition below.

## Decision

Six Bounded Contexts for MVP:

1. **Identity & Access** — users, ConnectedSource entities (Strava tokens), Strava OAuth login, JWT issuance.
2. **Activity Ingestion** — boundary with Strava. Webhooks, sync jobs, retries, idempotency, raw payloads.
3. **Workout Catalog** — canonical, normalized model of completed workouts. Splits, streams, sport metadata.
4. **Performance Analytics** — derived metrics (CTL/ATL/TSB, PRs, trends), time-series storage. TimescaleDB lives here.
5. **Training Planning** — plans, scheduled sessions, planned-vs-actual reconciliation.
6. **Notifications** — pure downstream consumer of events from other BCs; delivers via email/SSE.

See [Bounded Contexts](../architecture/bounded-contexts.md) for full descriptions and the BC map.

## Alternatives considered

### Merging Activity Ingestion into Workout Catalog

Rejected. The two have different languages (`RawActivityPayload` and `SyncCursor` vs `Workout` and `Stream`), different invariants (delivery reliability and rate-limit governance vs canonical workout integrity), and different change drivers (Strava API changes vs domain model evolution). Keeping them separate also gives a clean place for future multi-source ingestion without polluting the canonical workout model.

### Merging Workout Catalog and Performance Analytics

Rejected. Tempting because every analytics query "wants to join with workouts" — but this conflates write-side (what happened) with read-side (what it means). Different cadence (workouts are stable, metrics algorithms evolve), different storage characteristics (relational vs time-series), different consistency requirements (workouts are eventually consistent on ingest, analytics can be slightly stale). The CQRS-style separation is more code but architecturally cleaner and shows up well in event-driven storytelling.

### Adding a Social/Challenges BC

Rejected. See [ADR 0006](0006-no-social-challenges.md).

### Adding a Coaching BC

Deferred. Coach-style use cases (one user viewing multiple athletes' data) are anticipated but not built. The user/ConnectedSource model from [ADR 0004](0004-user-model-and-connected-sources.md) accommodates this — when implemented, coaching will likely add a new BC for "athlete-coach relationships" rather than reshape existing BCs.

## Consequences

**Positive:**
- Each BC has one reason to change. Strava API changes → only Ingestion. New metric formula → only Analytics. New notification channel → only Notifications.
- Event-driven flow is natural: `ActivityIngested` → `WorkoutPublished` → `MetricsRecomputed` + `SessionCompleted` → `PersonalRecordDetected` → notifications. Each step demonstrably independent. Events are delivered in-process via Spring Modulith (see [ADR 0008](0008-event-transport.md)).
- Schema-per-context (within shared PostgreSQL) gives logical isolation without operational overhead.
- Spring Modulith can enforce boundaries at build time via `ApplicationModules.verify()`.

**Negative:**
- More code than a single-package "service" approach. Six BCs means six modules, six sets of integration tests, six READMEs.
- Cross-BC operations (e.g. "show me workouts with their analytics") require either eventing into a read model or composition at the API layer — slightly more work than a SQL join.

**Risks:**
- Premature decomposition. A six-BC monolith for a single-developer pet project is overhead. Mitigation: this is a deliberate portfolio choice; the showcase value of explicit BCs outweighs the productivity cost.
- Boundaries drift. Without discipline, BCs leak into each other (e.g. Catalog directly querying Analytics tables). Mitigation: Spring Modulith verification tests in CI.

**Follow-ups:**
- Per-BC deep dives: each `contexts/{bc}/README.md` documents domain model, API, events, and schema.
- Event catalog: [events/catalog.md](../events/catalog.md).

## References

- Eric Evans, *Domain-Driven Design*, chapters 14–17 (Bounded Context, Context Mapping)
- Vaughn Vernon, *Implementing Domain-Driven Design*, chapter 2
- [ADR 0008: Event transport](0008-event-transport.md)
- [Spring Modulith reference documentation](https://docs.spring.io/spring-modulith/reference/)
