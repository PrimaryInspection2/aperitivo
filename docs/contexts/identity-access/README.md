# Bounded Context: Identity & Access

**Responsibility:** Who is the user, how did they authenticate, and how do we hold the credentials we need to access their data on external systems.

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — User, ConnectedSource, value objects, the `sub` claim
- [Database Schema](database.md) — tables, indices, partial unique index, migrations
- [Events](events.md) — `UserRegistered`, `IntegrationConnected`, `IntegrationRevoked`
- [API](api.md) — OAuth login, callback, current-user, logout
- [Sequence: Strava OAuth login](diagrams/sequence/strava-oauth-login.md)
- Technical notes: [token-management](../../technical-notes/token-management.md), [jwt-and-keys](../../technical-notes/jwt-and-keys.md)

## Summary

The IAM BC owns:
- The internal `User` record (UUID identity, decoupled from any external system's user ID).
- The `ConnectedSource` record (credentialed link from User to an external provider, currently Strava).
- The `TokenManager` component (sole access point for provider tokens — encryption, refresh, revocation, audit).
- Strava OAuth login (Spring Security OAuth2 client) and JWT issuance (Spring Security `JwtEncoder`, RS256).
- User profile and preferences.

> Style: entities are plain data; all logic (provisioning, token lifecycle, event publication) lives in services. See [ADR 0005](../../adr/0005-bounded-contexts.md).

## Key decisions

- [ADR 0009](../../adr/0009-identity-spring-security.md) — Identity via Spring Security with our own JWT issuer (supersedes ADR 0002).
- [ADR 0003](../../adr/0003-token-storage-strategy.md) — Strava tokens stored in our Postgres, AES-GCM encrypted.
- [ADR 0004](../../adr/0004-user-model-and-connected-sources.md) — Internal UUID + ConnectedSource entity.

## Events published

- `UserRegistered` (`user-registered.v1`)
- `IntegrationConnected` (`integration-connected.v1`)
- `IntegrationRevoked` (`integration-revoked.v1`)

## Events consumed

None from inside the system. External inputs: Strava OAuth callbacks and Strava deauthorization webhooks.

## Public interfaces

- REST API for the OAuth dance and user info.
- In-process interface `TokenManager.getValidAccessToken(userId)` — used exclusively by Activity Ingestion.

## Internal collaborators

- Spring Security OAuth2 client (Strava login) and resource server (JWT validation).
- `JwtEncoder` / `JwtDecoder` for issuing and validating our JWT.
- Encryption converter for token columns (AES-GCM).
- Optional Redis `jti` blacklist for logout/revocation.
