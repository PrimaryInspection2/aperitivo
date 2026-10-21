# ADR 0004: Internal user UUID + ConnectedSource entity

- **Status:** Accepted
- **Date:** 2026-05-20
- **Revised:** 2026-10-19 — provisioning and sign-in methods aligned with [ADR 0011](0011-identity-provider-signup.md)
- **Related:** [ADR 0011](0011-identity-provider-signup.md) (sign-in methods in `user_identities`; data sources connected on demand)

## Context

Aperitivo's MVP fetches all data from Strava. The simplest possible user model would use `strava_athlete_id` as the primary key throughout the system.

However, the system must extend — without a painful database migration — to:
- **Identity providers that are not data sources** (Google today, Apple later — [ADR 0011](0011-identity-provider-signup.md)).
- **Additional data sources** (Garmin Connect, Wahoo, COROS, Suunto, Polar, TrainingPeaks).
- **Coach-style use cases** (one identity, multiple athletes' data sources).

The economics are well understood: schema migrations on a populated production database with foreign-key cascades are expensive; designing the separation up front is cheap. Separating identity from access is also simply **correct normalization** — a `User` (who someone is) and a credentialed `ConnectedSource` (a link granting access to an external provider) have independent lifecycles, even with a single provider.

## Decision

**The internal `User` aggregate has its own UUID identifier (`user_id`), used as the foreign key throughout the system.** It is the stable, immutable handle for "this user" inside Aperitivo.

**Strava credentials are modeled as a separate `ConnectedSource` entity**, linked to `User` by `user_id`, with `provider = STRAVA` and `provider_user_id = strava_athlete_id`.

```
User
  id (UUID, PK)
  display_name
  email (nullable)
  created_at, updated_at

ConnectedSource
  id (UUID, PK)
  user_id (UUID, FK → User)
  provider (enum: STRAVA, [GARMIN, WAHOO, COROS, SUUNTO, POLAR, TRAININGPEAKS, …])
  provider_user_id (string, e.g. "12345678")
  access_token_encrypted (bytea)
  refresh_token_encrypted (bytea)
  access_token_expires_at (timestamptz)
  scopes (text[])
  status (enum: ACTIVE, REVOKED, ERROR)
  failure_count (int)
  last_error (text, nullable)
  last_refreshed_at, last_used_at, created_at

  UNIQUE (provider, provider_user_id)
```

The `provider` enum covers **OAuth-based, webhook-style partners** (Strava today; Garmin, Wahoo, COROS, Suunto, Polar, TrainingPeaks are realistic future candidates with similar token semantics). Mobile-native sources (Apple Health, Google Fit) and file uploads (FIT/GPX/TCX) have different semantics — no per-user OAuth token lifecycle — and are deliberately **not** modeled through this enum; if needed, they get their own representation.

Cardinality: a `User` has zero or more `ConnectedSource` rows. In the MVP the connect flow creates at most one (Strava); a newly registered user has none.

**`ConnectedSource` is for data access only.** How a user signs in is a separate entity, `UserIdentity` in `user_identities`, keyed by `(issuer, sub)` ([ADR 0011](0011-identity-provider-signup.md)). A `User` is provisioned on first login through an identity provider, and a `ConnectedSource` is created later, by the connect flow.

## Alternatives considered

- **`strava_athlete_id` as the primary key of `User`.** Rejected. Trivial in MVP but creates major migration debt: every foreign key in every BC and every event payload would carry it. Adding a non-Strava IdP later means either faking a `strava_athlete_id` for non-Strava users (gross) or a system-wide PK change (expensive and risky).
- **All Strava fields directly on the `User` table** (e.g. `strava_access_token`, `strava_status`, …). Rejected. Conflates identity with the credentials of one specific external system, and gives the `User` row a provider-specific `status` that does not belong to identity. Adding a second source means either more `garmin_*` columns (the table grows ugly fast) or extracting `ConnectedSource` later anyway — incurring the very migration we can cheaply avoid now.
- **A concrete `StravaConnection` entity with no `provider` enum** (a separate table, but Strava-specific rather than provider-agnostic). A reasonable middle option that still separates identity from access. Rejected in favor of the generic `ConnectedSource`: the abstraction cost is tiny (one enum, one extra lookup by primary key), the normalization and extension point are worth having regardless of whether a second provider ever ships, and a future second provider becomes an enum value plus an adapter rather than a second near-duplicate table or a later refactor.
- **A generic, untyped "external account" table.** Rejected. Schemaless typing pushes provider-specific validation into application code with no compile-time guarantees, while provider-specific scopes, expirations, and refresh semantics genuinely differ.

## Consequences

**Positive:**
- Adding a new identity provider later: a new `UserIdentity` provider value plus an ID-token verifier ([ADR 0011](0011-identity-provider-signup.md), [ADR 0012](0012-spa-mobile-stateless-api.md)) — no DB migration.
- Adding a new data source later: add a `provider` enum value plus a provider-specific OAuth adapter and webhook handler — no DB migration.
- Coach use case (future): one `User` linked to multiple `ConnectedSource` rows (different cardinality, same model).
- `User` is decoupled from any single external system — internal `user_id` is the lingua franca for all events, FKs, and APIs.

**Negative:**
- Slightly more code in MVP than the trivial "strava_athlete_id everywhere" approach.
- A small additional lookup (`User` × `ConnectedSource`) on token access. Negligible (primary-key access).

**Risks:**
- Drift over time: developers may be tempted to add Strava-specific fields directly to `User`. Mitigation: code review, explicit guidance in the IAM BC README, and tests asserting `User` has no provider-specific fields.

**Follow-ups:**
- Schema migrations: `users` and `connected_sources` tables defined in the IAM BC's migration changesets.
- Encryption columns and `TokenManager`: see [ADR 0003](0003-token-storage-strategy.md) and the token-management technical note.

## References

- [ADR 0003: Token storage strategy](0003-token-storage-strategy.md)
- [ADR 0009: Identity in-app with Spring Security and our own JWT issuer](0009-identity-spring-security.md)
- [ADR 0011: Identity-provider signup](0011-identity-provider-signup.md)
