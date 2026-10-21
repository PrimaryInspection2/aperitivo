# Bounded Context: Identity & Access

**Responsibility:** Who is the user, how did they sign in, and how do we hold the credentials we need to access their data on external systems.

Those are **two separate concerns**, and this BC keeps them apart. Signing in is done through an identity provider (Google) and produces a `UserIdentity`. Reading a user's training data is done through a data source (Strava) and produces a `ConnectedSource`. Signing in never connects a source; connecting a source never signs anyone in. See [ADR 0011](../../adr/0011-identity-provider-signup.md).

## Documents

- [README](README.md) — this overview
- [Domain Model](domain-model.md) — User, UserIdentity, ConnectedSource, RefreshToken, value objects, the `sub` claim
- [Database Schema](database.md) — tables (including `refresh_tokens`), indices, partial unique index, migrations
- [Events](events.md) — `UserRegistered`, `IntegrationConnected`, `IntegrationRevoked`
- [API](api.md) — Google login (ID-token exchange), refresh, logout, Strava connect, current-user, sources, identities; the security filter chain
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
- Google login as an ID-token exchange (`POST /api/auth/google`, verified by `GoogleIdTokenVerifier`), the client-driven Strava connect flow (`POST /api/connect/strava`, code exchanged server-side), and our own tokens: a 15-minute RS256 access token (`JwtEncoder`) and a rotating, server-stored refresh token (`refresh_tokens`).
- The `CurrentUser` principal — the only view of authentication other modules depend on.
- User profile and preferences.

The backend is a **stateless JSON API** for a same-origin React SPA and, later, a mobile app: one deny-by-default security filter chain, no `HttpSession`, no CORS, no backend OAuth redirects. See [ADR 0012](../../adr/0012-spa-mobile-stateless-api.md) and [ADR 0013](../../adr/0013-access-and-refresh-tokens.md).

> Style: entities are plain data; all logic (provisioning, token lifecycle, event publication) lives in services. See [ADR 0005](../../adr/0005-bounded-contexts.md).

## Key decisions

- [ADR 0009](../../adr/0009-identity-spring-security.md) — Identity in-app with Spring Security and our own JWT issuer.
- [ADR 0011](../../adr/0011-identity-provider-signup.md) — Signup via an identity provider; data sources connected on demand.
- [ADR 0012](../../adr/0012-spa-mobile-stateless-api.md) — SPA and mobile clients; the backend is a stateless API with ID-token login.
- [ADR 0013](../../adr/0013-access-and-refresh-tokens.md) — Short-lived access tokens with rotating refresh tokens.
- [ADR 0003](../../adr/0003-token-storage-strategy.md) — Strava tokens stored in our Postgres, AES-GCM encrypted.
- [ADR 0004](../../adr/0004-user-model-and-connected-sources.md) — Internal UUID + ConnectedSource entity.

## Events published

- `UserRegistered` (`user-registered.v1`) — a new account exists. Does **not** imply any data source.
- `IntegrationConnected` (`integration-connected.v1`) — a source is `ACTIVE`. The sole trigger for ingestion backfill.
- `IntegrationRevoked` (`integration-revoked.v1`)

The first two are never emitted together — see [events.md](events.md).

## Events consumed

None from inside the system. External inputs: the Google ID token presented to `POST /api/auth/google`, the Strava authorization code presented to `POST /api/connect/strava`, and Strava deauthorization webhooks (the last arrives via Activity Ingestion).

## Public interfaces

- REST API for login, token refresh and logout, Strava connect, user info, and source/identity management.
- `CurrentUser(UUID userId)` — the authenticated principal, built from our access token; other modules depend on it, never on JWT or OAuth types.
- In-process interface `TokenManager.getValidAccessToken(userId)` — used exclusively by Activity Ingestion.
- In-process interface `UserContactReader.emailFor(userId)` — used exclusively by Notifications, to address outbound email. Read-only, returns `Optional` (the address is nullable). See [domain-model.md](domain-model.md).

## Internal collaborators

- One stateless Spring Security filter chain acting as a resource server (our access token, `CurrentUser` converter, Problem Details `401`).
- `GoogleIdTokenVerifier` (`NimbusJwtDecoder` + `OidcIdTokenValidator`) for login; `RestClientAuthorizationCodeTokenResponseClient` with the `strava` `ClientRegistration` for the connect code exchange.
- `JwtEncoder` / `JwtDecoder` for issuing and validating our access token; `RefreshTokenService` for refresh-token rotation and revocation (Postgres).
- Encryption converter for token columns (AES-GCM). Identity rows hold no secrets; refresh-token rows hold only hashes.

