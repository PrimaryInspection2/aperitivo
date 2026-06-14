# ADR 0009: Identity via Spring Security with own JWT issuer (supersedes ADR 0002)

- **Status:** Accepted
- **Date:** 2026-06-14
- **Supersedes:** [ADR 0002](0002-identity-model.md)

## Context

[ADR 0002](0002-identity-model.md) decided that identity would be issued by **Keycloak**, bridged to Strava via a **custom Keycloak Authenticator SPI**. On review, that decision was reconsidered.

Aperitivo's login is **passwordless**: every user authenticates by completing a Strava OAuth flow. There is no password, no email/password registration, no "forgot password" in the MVP. This makes most of Keycloak's feature surface inapplicable:

- **Password reset** — N/A (no passwords).
- **Email verification** — N/A (Strava already verified the user's email).
- **Login themes** — N/A (the login UI is ours; the only login button redirects to Strava).
- **MFA** — not in MVP scope, and in a passwordless-OAuth flow it would itself require custom Keycloak Authentication Flow configuration, i.e. not free out of the box.

What we actually need from an identity layer in the MVP is narrow: issue a standard JWT, store a minimal user account, and support logout/session invalidation. Keycloak brings a separate service, a separate database, an Admin API integration, and a version-sensitive custom SPI — to deliver that narrow need.

Meanwhile Strava is **OAuth 2.0, not OIDC**, so Keycloak Identity Brokering (federation) works poorly, and the only clean Keycloak integration was the custom Authenticator SPI — the most code-heavy option.

## Decision

**Remove Keycloak. Implement identity with Spring Security 6 and our own JWT issuer.**

- **Strava login** is handled by Spring Security's OAuth2 client (`oauth2Login()` / OAuth2 client support), with a custom `OAuth2UserService` that processes the Strava callback.
- **User provisioning**: on first successful Strava OAuth, create a `User` (internal UUID) in our database, keyed off `strava_athlete_id` via the `ConnectedSource` (see [ADR 0004](0004-user-model-and-connected-sources.md)); on subsequent logins, resolve the existing user.
- **JWT issuance**: a Spring Security `JwtEncoder` (RS256) signs our own JWT. Claims are minimal: `sub` = internal `user_id` (UUID), `iss` = our domain, `iat`, `exp` (24–72h), `jti`.
- **JWT validation**: BCs act as a Spring Security resource server validating our JWT against our public key (JWKS endpoint optional for production).
- **Logout / revocation**: optional in MVP via a `jti` blacklist in Redis with TTL equal to the token's remaining lifetime. With short-lived tokens, MVP can also defer revocation.

The separation of concerns from ADR 0002 is preserved and even simplified:
- **Identity tokens (our JWT):** issued by us via Spring Security, used everywhere inside Aperitivo for authorization.
- **Data tokens (Strava access/refresh):** stored by us, used only by Activity Ingestion via the IAM `TokenManager` (see [ADR 0003](0003-token-storage-strategy.md)).
- **OAuth flow with Strava:** handled by our backend.

The JWT we emit is a standard OIDC-style JWT, so the choice of issuer is an internal detail invisible to API consumers.

## Alternatives considered

- **Keycloak + custom Authenticator SPI (the superseded ADR 0002 decision).** Rejected on review: most Keycloak features are inapplicable to passwordless Strava login; the SPI is the most code-heavy integration (~300–500 lines of version-sensitive code) and a niche skill relative to the target role; and it adds a whole service + database + Admin API integration to deliver a narrow need.
- **Keycloak Identity Brokering (Strava as federated IdP).** Rejected: Strava is OAuth 2.0, not OIDC; federation is awkward and would still need custom handling. (Already analyzed in ADR 0002 and ADR 0003.)
- **SaaS identity (Auth0, Clerk, Supabase Auth).** Rejected: vendor lock-in on a portfolio project, weak open-source fit (Apache 2.0), limited showcase value, and Strava is not a first-class provider in these platforms anyway.
- **Other self-hosted IdPs (Ory, Authentik, Authelia).** Rejected: same "extra service" cost as Keycloak with less maturity and weaker showcase value.

## Consequences

**Positive:**
- One fewer service (Keycloak) and one fewer database in the stack — significant for a single-VPS deployment.
- Roughly 600–800 fewer lines of identity code (no custom SPI, no realm automation, no Admin API client).
- Full control over JWT structure and claims.
- Simpler testing (no Keycloak Testcontainer; Spring Security test support only).
- Deep, broadly transferable Spring Security 6 / OAuth2 / JWT-issuer knowledge — well aligned with the target staff-level role.

**Negative:**
- We give up the custom Keycloak SPI as a portfolio artifact. (Compensated by Spring Security depth and, later, envelope-encryption/JWKS key-rotation work — see [ADR 0003](0003-token-storage-strategy.md) and the token-management technical note.)
- We own key management for the JWT signing key (RS256). Mitigation: env-sourced key in MVP, KMS/Vault-backed in production.
- Adding Google/Apple/email identity later means adding Spring Security `ClientRegistration`s and callback handling in our code rather than configuring an IdP in an admin console — more code, but all in one place and easy to test.

**Risks:**
- A future need for enterprise identity (B2B SSO, corporate IdP federation, full MFA) genuinely warrants Keycloak. Mitigation: because we emit a standard OIDC-style JWT, the issuer can be swapped to Keycloak later **without breaking API consumers** — they cannot tell which component minted the token.

**Follow-ups:**
- [ADR 0003](0003-token-storage-strategy.md) updated: data-token storage no longer references Keycloak federation as an alternative.
- [ADR 0004](0004-user-model-and-connected-sources.md) updated: user provisioning no longer routes through a Keycloak Authenticator SPI.
- Identity & Access deep dive to detail the OAuth2 client config, `JwtEncoder` setup, and key handling.

## References

- [ADR 0002: Identity model (superseded)](0002-identity-model.md)
- [ADR 0003: Token storage strategy](0003-token-storage-strategy.md)
- [ADR 0004: User model and ConnectedSource](0004-user-model-and-connected-sources.md)
- [Spring Security — OAuth2 Client](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/index.html)
- [Spring Security — JWT / JOSE support](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
