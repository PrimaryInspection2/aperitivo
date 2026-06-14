# Bounded Context: Identity & Access

**Responsibility:** Who is the user, how did they authenticate, and how do we hold the credentials we need to access their data on external systems.

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — *to be written* — User, ConnectedSource, value objects
- [API](api.md) — *to be written* — REST endpoints exposed by IAM
- [Events](events.md) — *to be written* — events published and consumed
- [Database Schema](database.md) — *to be written* — tables, indices, migrations

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

- `UserRegistered`
- `IntegrationConnected`
- `IntegrationRevoked`

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
