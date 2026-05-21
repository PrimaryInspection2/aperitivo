# Bounded Context: Identity & Access

**Responsibility:** Who is the user, how did they authenticate, and how do we hold the credentials we need to access their data on external systems.

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — *to be written* — User, ConnectedSource, value objects, aggregates
- [API](api.md) — *to be written* — REST endpoints exposed by IAM
- [Events](events.md) — *to be written* — events published and consumed
- [Database Schema](database.md) — *to be written* — tables, indices, migrations

## Summary

The IAM BC owns:
- The internal `User` aggregate (UUID identity, decoupled from any external system's user ID).
- The `ConnectedSource` aggregate (credentialed link from User to an external provider, currently Strava).
- The `TokenManager` component (sole access point for provider tokens — encryption, refresh, revocation, audit).
- The custom Keycloak Authenticator SPI (bridges Strava OAuth completion to Keycloak JWT issuance).
- User profile and preferences.

## Key decisions

- [ADR 0002](../../adr/0002-identity-model.md) — "Sign in with Strava" via custom Authenticator SPI.
- [ADR 0003](../../adr/0003-token-storage-strategy.md) — Tokens stored in our backend, not Keycloak federation.
- [ADR 0004](../../adr/0004-user-model-and-connected-sources.md) — Internal UUID + ConnectedSource entity.

## Events published

- `UserRegistered`
- `IntegrationConnected`
- `IntegrationRevoked`

## Events consumed

None from inside the system. External inputs: Strava OAuth callbacks, Strava deauthorization webhooks, Keycloak authentication flows.

## Public interfaces

- REST API for the OAuth dance and user info.
- In-process interface for `TokenManager.getValidAccessToken(userId, provider)` — used exclusively by Activity Ingestion.

## Internal collaborators

- Custom Keycloak Authenticator SPI (deployed as a separate JAR into Keycloak container, but logically part of this BC).
- Encryption layer for token columns.
