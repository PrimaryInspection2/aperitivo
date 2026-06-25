# Naming Conventions

Cross-cutting naming rules for **all** Bounded Contexts in Aperitivo. Every BC follows these; per-BC docs reference this file rather than restating it.

The goal is that a class name tells you which architectural layer it belongs to at a glance, and that the codebase reads consistently across modules.

## Layer suffixes

| Kind | Convention | Examples |
|---|---|---|
| JPA entity | `…Entity` | `UserEntity`, `ConnectedSourceEntity`, `WorkoutEntity` |
| DTO (API in/out, cross-layer transfer) | `…Dto` | `UserDto`, `ProfileUpdateDto`, `WorkoutSummaryDto` |
| Domain event (Spring Modulith) | `…Event` | `UserRegisteredEvent`, `WorkoutCreatedEvent`, `IntegrationRevokedEvent` |
| Service (logic, transactions) | `…Service` | `UserService`, `ConnectedSourceService`, `WorkoutService` |
| Repository (Spring Data JPA) | `…Repository` | `UserRepository`, `ConnectedSourceRepository` |
| REST controller | `…Controller` | `AuthController`, `WorkoutController` |

## Exceptions to the suffix rule

- **Role-signaling services may keep a domain-meaningful suffix** instead of `…Service` when the name communicates a specific responsibility. The canonical case is **`TokenManager`** — the single owner of token access. `Manager` here signals "sole gatekeeper of this resource," which is more informative than `TokenService`. Use this sparingly and only when the role name genuinely adds meaning.

- **Enums and value objects take no suffix.** They are domain types, not layered artifacts.
  - Enums: `Provider`, `SourceStatus`, `Sport`, `Channel`.
  - Value objects / immutable data holders: `StravaTokens`, `StravaProfile`, `StravaOAuthResult`, `PlannedTarget`.

## Domain events are plain records

Modulith events are Java `record`s. They do **not** extend Spring's `org.springframework.context.ApplicationEvent`. Naming a class `…ApplicationEvent` is forbidden — it collides conceptually with the Spring base class and misleads. Use `…Event`:

```java
public record UserRegisteredEvent(UUID userId, Instant occurredAt) {}
```

## Entities are data, services hold behavior

Per the project-wide style (Spring layered, not tactical DDD): `…Entity` classes are plain data — fields, accessors, JPA/Hibernate mapping annotations, and lifecycle/audit annotations only. No business methods. All logic lives in `…Service` classes. See [ADR 0005](../adr/0005-bounded-contexts.md).

"Entity" in Aperitivo docs therefore means *a data record managed by a service*, not a behavior-rich DDD aggregate root.

## Packages

Within each BC module, separate the public contract from internals so Spring Modulith can enforce boundaries:

```
com.aperitivo.<bc>
├── api/         ← published interface: DTOs, service interfaces, events other BCs consume
└── internal/    ← entities, repositories, service impls, controllers
```

Other BCs may depend only on `api`. Verified at build time by `ApplicationModules.verify()`.

## IDs and references

- Internal identifiers are `UUID`, named `id` on the entity and `<thing>Id` when referenced elsewhere (`userId`, `workoutId`).
- **References across aggregate boundaries are plain id fields (`UUID`), never JPA associations.** See the IAM domain model and [ADR 0004](../adr/0004-user-model-and-connected-sources.md) for the rationale; associations are used only *within* an aggregate (e.g. Catalog, Planning).
- Provider-specific external ids are `providerUserId` (string), never used as our internal identity or as a JWT `sub`.
