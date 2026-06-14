# ADR 0002: "Sign in with Strava" via Keycloak custom Authenticator SPI

- **Status:** Superseded by [ADR 0009](0009-identity-spring-security.md)
- **Date:** 2026-05-20

> **Superseded.** On review (June 2026), Keycloak was removed from the stack. Identity is now issued by Spring Security with our own JWT issuer. See [ADR 0009](0009-identity-spring-security.md) for the current decision and the reasoning behind the change. This document is retained, unedited below the line, for historical context.

---

## Context

Aperitivo's product positioning is "Strava extender" — every user must have a Strava account, because Strava is the data source. This frames the identity question: how does a user become authenticated to Aperitivo?

Requirements:
1. Frictionless onboarding — a Strava user should be able to start using Aperitivo in seconds.
2. Standard authentication infrastructure inside Aperitivo — services should authorize requests using a standard JWT, not custom session tokens.
3. We need access to the user's Strava API tokens for data fetching, but identity verification and data access are conceptually different concerns.
4. The model should not box us in if we later add Google/Apple/email identity providers (low priority but should be possible without a rewrite).

## Decision

User-facing UX: a single **"Sign in with Strava"** button. Behind the scenes:

1. The backend hosts a Strava OAuth 2.0 flow — handles authorization redirect, callback, code exchange.
2. On a successful Strava login, the backend:
   - Resolves or creates an internal `User` record (UUID), keyed off `strava_athlete_id`.
   - Stores Strava access + refresh tokens in a `ConnectedSource` entity (see [ADR 0003](0003-token-storage-strategy.md)).
   - Asks Keycloak to issue a standard OIDC JWT for that internal user.
3. The bridge between "Strava OAuth completed successfully" and "Keycloak issues a JWT" is a **custom Keycloak Authenticator SPI** — a Java module deployed into Keycloak that knows how to validate "this athlete completed a Strava OAuth flow that our backend authorized" and then drives Keycloak's standard token issuance.
4. The JWT issued by Keycloak is standard OIDC. Aperitivo's BCs validate it like any other JWT. It does **not** contain Strava tokens.

This gives us three architecturally separate concerns:
- **Identity tokens (JWT):** issued by Keycloak, used everywhere inside Aperitivo for authorization.
- **Data tokens (Strava access/refresh):** stored by us, used only by Activity Ingestion via the IAM `TokenManager`.
- **OAuth flow with Strava:** handled by our backend, not by Keycloak.

## Alternatives considered

- **Pure Keycloak Identity Brokering with Strava as a federated IdP.** Keycloak supports this out of the box — Strava would be configured as an OAuth IdP in Keycloak admin console; Keycloak would handle the OAuth dance and (optionally) store the Strava tokens in its `federated_identity` table. We rejected this because:
  - Strava is OAuth 2.0 only, not OIDC. Keycloak's federation works best with OIDC providers; with bare OAuth, it requires generic OAuth provider configuration with workarounds (user-info endpoint mapping, etc.) — comparable complexity to the SPI approach.
  - To fetch tokens from Keycloak's stored federated identity, every data-fetching service has to go through Keycloak (`/broker/{alias}/token` or Token Exchange API). Keycloak becomes a hot-path dependency for all ingestion.
  - Token Exchange is a Keycloak **Technology Preview** feature with non-stable API.
  - Domain modeling becomes awkward — the `ConnectedSource` lifecycle (revoked/error states, multi-source extension) is more naturally an Aperitivo domain concern than a Keycloak federation concern.
  - See [ADR 0003](0003-token-storage-strategy.md) for the full tradeoff analysis on tokens.
- **Build a homegrown identity service.** Rejected: gratuitous reinvention. Keycloak is a battle-tested OAuth/OIDC provider.
- **SaaS identity (Auth0, Clerk, Supabase Auth).** Rejected: this is a portfolio project; we want to *demonstrate* identity infrastructure work, not outsource it. Custom SPI development is a deliberate learning target.

## Consequences

**Positive:**
- Clean separation between identity tokens and data tokens. Compromised data tokens do not grant impersonation; compromised identity tokens do not grant Strava API access.
- Keycloak is **not** on the hot path for data operations. It's only touched at login.
- Future-proof: adding Google/Apple/email as identity providers later is a Keycloak config change (new IdP, new authenticator) plus a "Connect Strava" data-OAuth flow split from the identity flow. The internal `User` model already separates identity from connected sources.
- Strong portfolio signal: custom Keycloak SPI is a non-trivial piece of infrastructure work with deep talking points.

**Negative:**
- More code to write and maintain than out-of-the-box federation.
- SPI development is Keycloak-version-sensitive; major Keycloak upgrades may require SPI adjustments.

**Risks:**
- The custom Authenticator must rigorously validate that a Strava OAuth flow actually completed for the claimed athlete (no spoofing). Mitigation: the Authenticator only trusts state that originated from our own backend's Strava OAuth callback, never trusts user-supplied claims.

> **Note (superseded):** the negatives and risks above — extra code, version-sensitivity, the whole Keycloak service for a narrow passwordless need — are precisely what motivated the move to Spring Security in [ADR 0009](0009-identity-spring-security.md).

**Follow-ups:**
- [ADR 0003](0003-token-storage-strategy.md): where Strava tokens live.
- [ADR 0004](0004-user-model-and-connected-sources.md): the internal user model and ConnectedSource entity.

## References

- [ADR 0009: Identity via Spring Security (supersedes this)](0009-identity-spring-security.md)
- [Keycloak Server Developer Guide — Authentication SPI](https://www.keycloak.org/docs/latest/server_development/#_auth_spi)
- [Strava API — Authentication](https://developers.strava.com/docs/authentication/)
