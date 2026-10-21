# ADR 0009: Identity in-app with Spring Security and our own JWT issuer

- **Status:** Accepted
- **Date:** 2026-06-14
- **Revised:** 2026-10-19 — login mechanism and token lifecycle aligned with [ADR 0012](0012-spa-mobile-stateless-api.md) and [ADR 0013](0013-access-and-refresh-tokens.md)
- **Replaces:** [ADR 0002](../archived/adr/0002-identity-model-superseded.md) (archived — Keycloak with a custom Authenticator SPI)
- **Related:** [ADR 0011](0011-identity-provider-signup.md) (who authenticates the user), [ADR 0012](0012-spa-mobile-stateless-api.md) (how clients log in), [ADR 0013](0013-access-and-refresh-tokens.md) (token lifecycle)

## Context

Aperitivo needs an identity layer that does three things: establish who the user is through an external identity provider, keep a minimal account of its own, and authenticate every API request.

Login is **passwordless**. A user proves who they are through an identity provider (Google, [ADR 0011](0011-identity-provider-signup.md)); Aperitivo never handles a password. That makes most of a full identity server's feature set inapplicable:

- **Password reset, password policies, credential storage:** not applicable, there are no passwords.
- **Email verification:** the identity provider asserts `email_verified`.
- **Login themes and hosted login pages:** the only login UI is the provider's sign-in button in our own client.
- **MFA:** delegated to the identity provider's account security. It is not an MVP requirement of ours.

What remains is narrow: verify a provider assertion, provision or resolve a `User`, issue a standard token, and validate it on every request. An identity server (Keycloak or similar) would bring a separate service, a separate database and an admin integration to deliver that narrow need.

## Decision

**Identity lives inside the application. Spring Security verifies identity-provider assertions and validates our tokens, and we issue our own RS256-signed JWTs. There is no external identity server.**

- **Verifying the identity provider.** The client obtains an OIDC ID token from the identity provider, and the backend verifies it with Spring Security's JOSE support (`NimbusJwtDecoder`, `OidcIdTokenValidator`). The exact login flow is [ADR 0012](0012-spa-mobile-stateless-api.md).
- **Provisioning.** A verified `(issuer, sub)` resolves the existing `User` or creates a new one. The internal `user_id` is a UUID that holds nothing provider-specific ([ADR 0004](0004-user-model-and-connected-sources.md), [ADR 0011](0011-identity-provider-signup.md)).
- **Token issuance.** A Spring Security `JwtEncoder` signs our access tokens with **RS256**. Claims are minimal: `sub` is the internal `user_id`, plus `iss`, `aud`, `iat`, `exp` and `jti`. Lifetimes, refresh tokens and logout are [ADR 0013](0013-access-and-refresh-tokens.md).
- **Token validation.** The application is a Spring Security **resource server** that validates our JWT against our public key. Bounded contexts see only a `CurrentUser` principal. A JWKS endpoint publishes the public key for any separately deployed validator.
- **RS256 rather than HS256.** Validation needs only the public key, so other components (and, later, separately deployed services) can verify tokens without holding the signing key.

Identity tokens and data tokens stay separate:
- **Identity tokens (ours):** issued by us and used for every authorization decision inside Aperitivo.
- **Data tokens (Strava access and refresh):** stored by us, encrypted, and used only by Activity Ingestion through IAM's `TokenManager` ([ADR 0003](0003-token-storage-strategy.md)).

The JWT we emit is a standard OIDC-style JWT, so which component issues it is an internal detail invisible to API consumers.

## Alternatives considered

- **Keycloak with a custom Authenticator SPI** (the archived [ADR 0002](../archived/adr/0002-identity-model-superseded.md)). Rejected: most Keycloak features do not apply to passwordless provider login; the SPI is the most code-heavy integration (version-sensitive custom code); and it adds a whole service, a database and an Admin API integration for a narrow need.
- **Keycloak identity brokering** (the provider federated into Keycloak, Keycloak issuing our tokens). Rejected for the MVP: the same extra service and database, for a single identity provider.
- **SaaS identity (Auth0, Clerk, Supabase Auth).** Rejected: vendor lock-in on a portfolio project, weak open-source fit, and limited showcase value.
- **Other self-hosted identity providers (Ory, Authentik, Authelia).** Rejected: the same extra-service cost as Keycloak, with less maturity.
- **Spring Authorization Server** (standard `/oauth2/token` endpoints with refresh grants, the identity provider upstream). Rejected for the MVP: a full authorization server for one first-party client family. Revisit if third-party clients appear ([ADR 0012](0012-spa-mobile-stateless-api.md)).

## Consequences

**Positive:**
- One fewer service and database in the stack, which is significant for a single-VPS deployment.
- Full control over token structure, claims and lifetimes.
- Simpler testing: Spring Security test support only, no identity-server Testcontainer.
- Deep, transferable Spring Security, OAuth 2.0, OIDC and JWT-issuer knowledge, which is the portfolio goal.

**Negative:**
- **We own the signing key** (RS256). Mitigation: an environment-sourced key in the MVP; KMS or Vault custody and `kid`-based rotation later ([jwt-and-keys.md](../technical-notes/jwt-and-keys.md)).
- **Adding identity providers is code**: a verifier per provider, rather than configuration in an admin console. It all lives in one place and is easy to test.
- **We own the token endpoints** (`/api/auth/*`) instead of getting them from an authorization server ([ADR 0013](0013-access-and-refresh-tokens.md)).

**Risks:**
- **A future need for enterprise identity** (B2B SSO, corporate IdP federation, full MFA) genuinely warrants an identity server. Mitigation: we emit a standard OIDC-style JWT, so the issuer can be swapped later **without breaking API consumers**.

## References

- [ADR 0003: Token storage strategy](0003-token-storage-strategy.md)
- [ADR 0004: User model and ConnectedSource](0004-user-model-and-connected-sources.md)
- [ADR 0011: Identity-provider signup](0011-identity-provider-signup.md)
- [ADR 0012: SPA and mobile clients; stateless API with ID-token login](0012-spa-mobile-stateless-api.md)
- [ADR 0013: Short-lived access tokens with rotating refresh tokens](0013-access-and-refresh-tokens.md)
- [jwt-and-keys.md](../technical-notes/jwt-and-keys.md)
- [Spring Security — Resource Server JWT](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/resource-server/jwt.html)
