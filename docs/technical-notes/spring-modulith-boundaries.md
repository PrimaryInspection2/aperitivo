# Technical Note: Spring Modulith Boundaries

How the Bounded Context boundaries that the rest of the documentation describes are **enforced at
build time** — not just documented and hoped-for. This is the note that turns "no BC reads another
BC's database", "no JPA association crosses a boundary", and "inter-BC flow is events + published
ports" from prose into a failing test when violated.

Complements [ADR 0005](../adr/0005-bounded-contexts.md) (the modular-monolith decision) and
[ADR 0008](../adr/0008-event-transport.md) (in-process events) with the *mechanics*.

## Why this matters

A modular monolith is only modular if something stops a tired developer from `@Autowired`-ing a
repository across a boundary at 6pm. Package-private visibility helps but is not enough (Spring
makes beans reachable regardless), and code review is not a build gate. Spring Modulith makes the
boundaries **executable**: the module structure is derived from package layout, the allowed
dependencies are declared, and `ApplicationModules.verify()` fails the build on any violation. The
boundaries we drew across six BCs are worth exactly as much as this test.

## Module structure — packages are the boundary

Each Bounded Context is a Spring Modulith **application module** = a direct sub-package of the
application root:

```
com.aperitivo
├── identity              ← Identity & Access BC
├── ingestion             ← Activity Ingestion BC
├── catalog               ← Workout Catalog BC
├── analytics             ← Performance Analytics BC
├── planning              ← Training Planning BC
├── notifications         ← Notifications BC
└── shared                ← shared kernel (see below)
```

The rule Modulith applies by default:

- **The base package of a module is its API.** Types directly in `com.aperitivo.catalog` are
  visible to other modules.
- **Sub-packages are internal.** Types in `com.aperitivo.catalog.internal` (entities, repositories,
  service implementations) are **not** allowed to be referenced from another module. A reference
  from `planning` into `catalog.internal` is a verification failure.

So the physical layout encodes the boundary: put the `WorkoutEntity`, `WorkoutRepository`, and
service impls under `catalog.internal`, and no other BC can touch them — the compiler allows it, but
`verify()` does not.

### Suggested per-BC internal layout

```
com.aperitivo.catalog
├── WorkoutCreatedEvent.java        ← published event (API — other modules consume it)
├── RawPayloadReader.java           ← NO: that's Ingestion's port; shown for contrast
├── package-info.java               ← @ApplicationModule metadata (optional)
└── internal
    ├── WorkoutEntity.java          ← never referenced cross-module
    ├── WorkoutRepository.java
    ├── ActivityNormalizationService.java
    └── WorkoutQueryService.java
```

Events and any deliberately-published port live in the **base** package; everything else lives in
`internal`. This is the single most important convention in the codebase for keeping the boundaries
real.

## What crosses a boundary — and what must not

Three legal channels between BCs, all visible in the docs, all enforceable here:

1. **Domain events** (asynchronous) — a module publishes an event type from its base package; other
   modules consume it via `@ApplicationModuleListener`. The dependency is on the **event type**, not
   on the publisher's internals.
2. **Published ports / named interfaces** (synchronous read) — a narrow interface in a module's base
   package, deliberately exposed. Examples in this system:
   - IAM's `TokenManager.getValidAccessToken(userId)` — Ingestion's only synchronous reach into IAM.
   - Ingestion's `RawPayloadReader.read(rawPayloadId)` — how Catalog and Analytics read the archived
     payload without HTTP.
3. **Shared kernel** (`com.aperitivo.shared`) — a small set of types every module may depend on:
   `UserId`/value objects, the `SportType` enum (Catalog's, promoted to shared because Planning
   reuses it as ubiquitous language), common error types. The shared module depends on **nothing**.

Everything else is forbidden, and `verify()` is what forbids it:

- ❌ A repository or entity referenced across modules (the cross-BC DB read).
- ❌ A JPA association (`@ManyToOne`/`@OneToMany`) whose target is another module's entity (the
  cross-BC foreign key). These are id-refs (`UUID`/`long`) precisely so this never compiles into an
  association.
- ❌ A service impl in `internal` autowired from another module.
- ❌ A cyclic dependency between modules.

## Named interfaces — exposing more than the base package

Sometimes a module needs to expose an API type that naturally lives in a sub-package. Modulith's
**named interfaces** (`@NamedInterface`) let a specific sub-package be marked as part of the module's
API without opening up all of `internal`. Used sparingly here — the default "base package is the
API" covers almost everything; a named interface is the escape hatch for, e.g., a port whose
implementation and interface are colocated.

## Allowed-dependency direction

The dependency graph is acyclic and flows roughly with the data:

```
identity ──(IntegrationConnected/Revoked)──▶ ingestion
ingestion ──(ActivityIngested/Deleted)─────▶ catalog
ingestion ──(RawPayloadReader port)────────▶ catalog, analytics   (read)
identity  ──(TokenManager port)────────────▶ ingestion            (read)
catalog ──(WorkoutCreated/Updated/Deleted)─▶ analytics, planning
analytics ──(PersonalRecordSet)────────────▶ notifications
planning ──(Session* events)───────────────▶ notifications
* ──(IngestionFailed/IntegrationRevoked/…)─▶ notifications
```

Notifications is a pure **sink** — it depends on (consumes events from) everyone and is depended on
by no one. IAM is a pure **source** of identity. No cycles. `verify()` enforces acyclicity, so an
accidental back-reference (e.g. Catalog reaching into Notifications) fails the build.

> Event dependencies are special: consuming `WorkoutCreatedEvent` makes `analytics` depend on the
> **event type** declared in `catalog`'s base package, not on `catalog`'s internals. Modulith
> understands this and counts it as an allowed module dependency. The event type is the contract.

## Enforcing it in CI — `ApplicationModules.verify()`

A single test, run in CI on every build, is the gate:

```java
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(AperitivoApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();   // fails on illegal cross-module references, cycles, internal access
    }

    @Test
    void writesDocumentation() {
        new Documenter(modules)
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml();   // generates C4-ish module diagrams from reality
    }
}
```

- **`verify()`** is the enforcement. If anyone introduces a forbidden dependency — a cross-BC
  repository autowire, an association into another module's entity, a cycle — this test goes red and
  the build fails. This is what makes the boundaries in every BC doc *true* rather than aspirational.
- **`Documenter`** is a bonus: it generates module dependency diagrams from the actual code, so the
  architecture diagrams can be kept honest (the C4 Level-3 component views, currently *to be
  written*, can be seeded from this output).

[Phase 0 of the roadmap](../business/roadmap.md) lists `ApplicationModules.verify()` in CI as an
exit criterion — it is wired in before any BC logic exists, so the boundaries are enforced from the
first commit, not retrofitted.

## Verifying event listeners and transactions

Modulith also provides:

- **`@ApplicationModuleListener`** — a meta-annotation combining `@Async` +
  `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional`. This is *the* way BCs
  consume each other's events: after the publisher commits, asynchronously, in the listener's own
  transaction. Every consumer in the docs uses it.
- **The event publication registry** (`event_publication` table) — the Outbox. Modulith writes a row
  in the **publisher's** transaction and marks it complete when the listener finishes; incomplete
  rows are retried (e.g. on restart). This is what gives the at-least-once delivery the docs assume,
  with no broker ([ADR 0008](../adr/0008-event-transport.md)).
- **`@ApplicationModuleTest`** — bootstraps a single module in isolation for integration tests,
  with the rest of the application stubbed. Lets each BC be tested at its boundary.

## How this maps onto the project's stated rules

| Stated rule (appears throughout the docs) | Enforced by |
|---|---|
| No BC reads another BC's database | `internal` repositories unreachable; `verify()` |
| No JPA association crosses a BC boundary | id-refs (`UUID`/`long`), never `@ManyToOne` to another module's entity; `verify()` catches a slip |
| Inter-BC flow is events + published ports | events in base package; ports as named interfaces; everything else `internal` |
| Each BC has its own schema | a convention checked operationally (per-BC Flyway), reinforced by the package boundary |
| Events are in-process, at-least-once, after commit | `@ApplicationModuleListener` + `event_publication` Outbox |
| The dependency graph is acyclic | `verify()` fails on cycles |

## What is deliberately NOT done

- **No runtime boundary enforcement / separate classloaders.** Enforcement is build-time
  (`verify()`), which is sufficient for a monolith and keeps runtime simple. Extraction to separate
  services later is the runtime boundary, if ever needed.
- **No OSGi / Java Platform Module System (JPMS).** Modulith's package-based modules are lighter and
  match the team's needs; JPMS would add ceremony without buying more than `verify()` already gives.
- **No per-module Spring context.** One application context; modules are a logical/architectural
  construct, not a runtime isolation boundary in MVP.

## Related

- [ADR 0005: Bounded contexts](../adr/0005-bounded-contexts.md) — the modular-monolith decision
- [ADR 0008: Event transport](../adr/0008-event-transport.md) — in-process events, the Outbox
- [Idempotency and outbox patterns](idempotency-and-outbox.md) — *to be written* — consumer-side Inbox/dedup detail
- [Roadmap, Phase 0](../business/roadmap.md) — `verify()` wired into CI from the start
