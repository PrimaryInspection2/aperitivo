# Bounded Context: Identity & Access

**Responsibility:** Who is the user, how did they sign in, and how do we hold the credentials we need to access their data on external systems.

Those are **two separate concerns**, and this BC keeps them apart. Signing in is done through an identity provider (Google) and produces a `UserIdentity`. Reading a user's training data is done through a data source (Strava) and produces a `ConnectedSource`. Signing in never connects a source; connecting a source never signs anyone in. See [ADR 0011](../../adr/0011-identity-provider-signup.md).

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — User, UserIdentity, ConnectedSource, value objects, the `sub` claim
- [Database Schema](database.md) — tables, indices, partial unique index, migrations
- [Events](events.md) — `UserRegistered`, `IntegrationConnected`, `IntegrationRevoked`
- [API](api.md) — Google login, Strava connect, current-user, sources, identities, logout
- [Sequence: Google login](diagrams/sequence/google-login.md)
- [Sequence: Strava connect](diagrams/sequence/strava-connect.md)
- [Sequence: revocation](diagrams/sequence/revocation-flow.md), [Sequence: token refresh](diagrams/sequence/token-refresh.md)
- Technical notes: [token-management](../../technical-notes/token-management.md), [jwt-and-keys](../../technical-notes/jwt-and-keys.md)

## Summary

The IAM BC owns:
- The internal `User` record (UUID identity, decoupled from any external system's user id).
- The `UserIdentity` record (how a user signs in: `(provider, sub)` from an identity provider; no tokens).
- The `ConnectedSource` record (credentialed link from User to a data provider, currently Strava).
- The `TokenManager` component (sole access point for provider tokens — encryption, refresh, revocation, audit).
- The `UserContactReader` port (sole access point for the account's contact address).
- Google OIDC login (Spring Security `oauth2Login`), the Strava connect flow (Spring Security `oauth2Client`), and JWT issuance (`JwtEncoder`, RS256).
- User profile and preferences.

> Style: entities are plain data; all logic (provisioning, token lifecycle, event publication) lives in services. See [ADR 0005](../../adr/0005-bounded-contexts.md).

## Key decisions

- [ADR 0011](../../adr/0011-identity-provider-signup.md) — Signup via an identity provider; data sources connected on demand (supersedes ADR 0010, amends 0004 and 0009).
- [ADR 0009](../../adr/0009-identity-spring-security.md) — Identity via Spring Security with our own JWT issuer (supersedes ADR 0002).
- [ADR 0003](../../adr/0003-token-storage-strategy.md) — Strava tokens stored in our Postgres, AES-GCM encrypted.
- [ADR 0004](../../adr/0004-user-model-and-connected-sources.md) — Internal UUID + ConnectedSource entity.

## Events published

- `UserRegistered` (`user-registered.v1`) — a new account exists. Does **not** imply any data source.
- `IntegrationConnected` (`integration-connected.v1`) — a source is `ACTIVE`. The sole trigger for ingestion backfill.
- `IntegrationRevoked` (`integration-revoked.v1`)

The first two are no longer emitted together — see [events.md](events.md).

## Events consumed

None from inside the system. External inputs: the Google OIDC callback, the Strava connect callback, and Strava deauthorization webhooks (the last arrives via Activity Ingestion).

## Public interfaces

- REST API for the two OAuth flows, user info, and source/identity management.
- In-process interface `TokenManager.getValidAccessToken(userId)` — used exclusively by Activity Ingestion.
- In-process interface `UserContactReader.emailFor(userId)` — used exclusively by Notifications, to address outbound email. Read-only, returns `Optional` (the address is nullable). See [domain-model.md](domain-model.md).

## Internal collaborators

- Spring Security `oauth2Login` (Google sign-in), `oauth2Client` (Strava connect), and resource server (JWT validation).
- `JwtEncoder` / `JwtDecoder` for issuing and validating our JWT.
- Encryption converter for token columns (AES-GCM). Identity rows hold no secrets.
- Optional Redis `jti` blacklist for logout/revocation.

