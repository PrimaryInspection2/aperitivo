# Operations: Security

The security posture of Aperitivo in operation — the threat model, the attack surface, secrets
management, and how the security-relevant decisions scattered across the docs add up. This note
**consolidates**; the mechanisms live in their own notes
([encryption-at-rest](../technical-notes/encryption-at-rest.md),
[jwt-and-keys](../technical-notes/jwt-and-keys.md),
[token-management](../technical-notes/token-management.md)) and this points at them.

## What we're protecting

In priority order:

1. **Users' Strava tokens** — the crown jewels. A leak grants API access to users' Strava accounts.
   Stored AES-GCM-encrypted in Postgres ([encryption-at-rest.md](../technical-notes/encryption-at-rest.md)).
2. **Our JWT signing key** (RS256 private key) — a leak lets an attacker mint valid identities. Held
   as a secret, env-sourced in MVP / KMS-backed in production ([jwt-and-keys.md](../technical-notes/jwt-and-keys.md)).
3. **Users' refresh tokens** — a stolen one keeps a session alive for up to its family's lifetime
   until reuse is detected. Stored only as SHA-256 hashes in `refresh_tokens`; on the web the token
   lives in an `HttpOnly` cookie that no script can read
   ([ADR 0013](../adr/0013-access-and-refresh-tokens.md), [jwt-and-keys.md](../technical-notes/jwt-and-keys.md#the-refresh-token)).
4. **The Strava client secret** — a leak compromises the whole app's Strava integration. It never
   leaves the backend: the SPA drives the browser leg of the connect flow, the backend does the code
   exchange ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md) §4).
5. **User data** (workouts, analytics) — lower sensitivity but still private.

The data we *don't* heavily protect (workout summaries, analytics) is a deliberate scoping: encrypt
credentials and secrets, keep domain data queryable
([encryption-at-rest.md](../technical-notes/encryption-at-rest.md)).

## Attack surface

| Surface | Exposure | Primary defense |
|---|---|---|
| Login (`POST /api/auth/google`) | public | Google ID token verified by Spring Security components (signature via Google JWKS, `iss`, `aud`, `azp`, `exp`, `email_verified`, optional `nonce`); JSON-only, no CORS |
| Refresh / logout (`POST /api/auth/refresh`, `/api/auth/logout`) | public, refresh-cookie-gated | opaque refresh token looked up by hash; rotation + reuse detection; cookie `SameSite=Strict`, `Path=/api/auth`; JSON-only, no CORS |
| Strava connect (`POST /api/connect/strava`) | public, JWT-gated | bearer token required; `user_id` from the bearer only; `state` verified by the SPA before the code is sent; code exchanged server-side with the `client_secret` |
| JWKS (`GET /.well-known/jwks.json`) | public | serves public keys only |
| Webhook receiver (`POST /api/webhooks/strava`) | public | `subscription_id` filter + authenticated re-fetch as the real trust boundary |
| Webhook validation (`GET …`) | public | `hub.verify_token` match |
| Authenticated API (`/api/**`) | public, JWT-gated | RS256 JWT validation per resource-server BC |
| SSE stream (`/api/sse/events`) | public, JWT-gated | JWT validation; per-user emitter scoping |
| Admin/ops endpoints (Actuator) | internal | network-restricted, not public; only `/actuator/health` is `permitAll`, everything else not explicitly listed is `denyAll` |

### The webhook trust model (the subtle one)

The webhook endpoint is public and has **no strong inbound authentication** (Strava defines no
per-delivery signature with certainty — the `X-Strava-Signature` question, see
[webhooks.md](../integrations/strava/webhooks.md)). This is **safe by design**, not an oversight: the
webhook body carries only identifiers, and we re-fetch the real activity with our own OAuth token. A
forged webhook can at most cause a wasted, rate-limited, 404-safe `GET`. **The trust boundary is the
authenticated call to Strava, not the inbound webhook.** If `X-Strava-Signature` turns out to be
present, it becomes a cheap additional receipt-time guard — strict hardening, not a fix.

## Secrets management

| Secret | MVP | Production target |
|---|---|---|
| Token-column encryption key | env var / mounted secret | KMS/Vault envelope (KEK wraps DEK) |
| JWT RS256 signing key | env-sourced PEM | KMS/Vault-backed |
| Strava client secret | env var | secret manager |
| DB / Redis credentials | env var | secret manager |

The consistent trajectory: **env-sourced in MVP, KMS/Vault-backed in production** — true for both the
token-encryption key and the JWT signing key, the two faces of secrets-management maturation
([encryption-at-rest.md](../technical-notes/encryption-at-rest.md),
[jwt-and-keys.md](../technical-notes/jwt-and-keys.md)). No secret is ever committed to git or baked
into an image; all are injected at deploy time.

## Authentication & authorization

- **Login:** the client (Google Identity Services on the web, the native SDK on mobile) obtains a
  Google **ID token** and exchanges it at `POST /api/auth/google`. The backend verifies it in
  `GoogleIdTokenVerifier`, resolves or provisions the `User` by `(issuer, sub)` and issues our tokens.
  No `oauth2Login`, no redirect callback, no `HttpSession`
  ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md)). A Google ID token is never accepted as an API
  bearer token.
- **Authentication:** our RS256 **access token** (15 min, `aud` = `aperitivo-api`), sent as
  `Authorization: Bearer` and validated by signature, `iss`, `aud` and `exp` only — no per-request
  lookup. `sub` = internal `user_id`. The SPA keeps it in memory; a **rotating refresh token**
  (`HttpOnly` cookie on the web) obtains new ones
  ([ADR 0013](../adr/0013-access-and-refresh-tokens.md), [jwt-and-keys.md](../technical-notes/jwt-and-keys.md)).
- **Security filter chain:** one stateless chain, **deny by default** — the `/api/auth/*` endpoints,
  JWKS, `/actuator/health` and `/error` are `permitAll`, `/api/**` requires a bearer token, anything
  else is `denyAll`. No `securityMatcher`, `sessionManagement(STATELESS)`, and no response sets
  `JSESSIONID`. Unauthenticated requests get `401` Problem Details
  ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md) §3).
- **CSRF:** protection is **disabled**. API requests authenticate by a header the browser never
  attaches by itself; the only cookie-borne credential is the refresh cookie, protected by
  `SameSite=Strict`, `Path=/api/auth`, JSON-only endpoints (a cross-origin JSON `POST` needs a CORS
  preflight) and **CORS being off** — the SPA and the API share one origin behind the reverse proxy.
- **Logout:** `POST /api/auth/logout` revokes the refresh-token family and clears the cookie; the
  access token stays valid for at most 15 minutes, which is accepted. No `jti` denylist in the MVP
  ([ADR 0013](../adr/0013-access-and-refresh-tokens.md) §4–5).
- **Authorization:** MVP is single-user-type — every authenticated user accesses **only their own**
  data, enforced by scoping every query to the JWT's `user_id` (never a client-supplied id). There
  is no role model yet; when one arrives it is an additive JWT claim.
- **Ownership checks:** endpoints return `404` (not `403`) for resources not owned by the caller, so
  existence of other users' data isn't leaked (documented per BC API).

## Encryption

- **In transit:** HTTPS everywhere (TLS terminated at nginx, which also routes `/` to the SPA and
  `/api/**` to Spring on the same origin); the SSE stream and webhook callback are HTTPS. Strava
  requires HTTPS callback URLs. The refresh cookie is `Secure`.
- **At rest:** AES-GCM column encryption for token columns; the rest of the DB relies on
  deployment-level disk encryption if present ([encryption-at-rest.md](../technical-notes/encryption-at-rest.md)).

## Audit logging

- **Token access is audited** — every use of a decrypted token logs the *fact* (user, operation,
  `trace_id`), never the value ([token-management.md](../technical-notes/token-management.md)).
- **Security-significant transitions** logged at WARN: source REVOKED, JWT validation failures
  (by reason), refresh-token reuse (family revoked), repeated 401s from Strava.
- **Logs never contain secrets** — no access, refresh or Google ID tokens, no Strava codes — enforced
  as a policy and a review item
  ([observability.md](observability.md)).

## GDPR / data lifecycle (MVP posture)

- **User deletion** is transactional — because tokens and user data live in the same Postgres,
  deleting a user cascades through their `UserIdentity` rows (sign-in methods) and `ConnectedSource`
  (data-source tokens), and the relevant per-BC data by `user_id`. (A full GDPR export/erase endpoint
  is post-MVP, noted in several BC APIs.)
- **Personal data is not duplicated across BCs.** The contact address lives in exactly one column
  (`users.email`) and is read by Notifications through the `UserContactReader` published port at
  delivery time. It is deliberately kept off event payloads: Spring Modulith serializes payloads
  into `event_publication`, so an address on an event would be copied durably into the outbox of
  every producing module and erasure would have to reach into JSON blobs. Same reasoning bars a
  Notifications-side projection of it
  ([IAM domain-model](../contexts/identity-access/domain-model.md)).
- **Token revocation on disconnect** — disconnecting a source revokes at Strava and marks `REVOKED`;
  retained vs cleared tokens per policy ([token-management.md](../technical-notes/token-management.md)).
- **Raw payload retention** — `RawActivityPayload` is kept as replay source of truth; a retention
  policy to age it out is a documented lever, not MVP ([timescaledb-schema.md](../technical-notes/timescaledb-schema.md)).

## Threat model summary

| Threat | Defended? | By |
|---|---|---|
| DB dump leak | ✅ | token columns encrypted; useless without key/KEK |
| Forged webhook | ✅ | identifiers-only + authenticated re-fetch; worst case a wasted GET |
| Stolen access token | ⚠️ partial | 15-minute lifetime; held in memory only; no immediate revoke in MVP (`jti` kept for a future denylist) |
| Stolen refresh token | ✅ (mitigated) | `HttpOnly` path-scoped cookie; rotation on every use, reuse revokes the whole family; hashed at rest |
| XSS in the SPA | ⚠️ partial | access token in memory, refresh token unreadable by script; CSP and dependency hygiene limit injection ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md)) |
| Signing-key leak | ✅ (mitigated) | env→KMS custody; rotation via JWKS `kid` overlap |
| CSRF on Strava connect (forged callback) | ✅ | `state` generated and verified by the SPA; `user_id` from the bearer only |
| CSRF on the API / refresh cookie | ✅ | bearer header auth; refresh cookie `SameSite=Strict`, JSON-only, no CORS |
| Login CSRF / replayed Google ID token | ⚠️ partial | JSON-only, no CORS, optional `nonce`; ID token is audience-bound and short-lived |
| Cross-user data access | ✅ | every query scoped to JWT `user_id`; 404 on non-owned |
| Compromised live host (key in memory) | ⚠️ partial | MVP env-key in memory; envelope encryption shrinks blast radius |
| Strava API suspension (availability) | ⚠️ | rate-limit governance; single-dependency risk is accepted ([overview](../architecture/overview.md)) |

The honest summary: the design defends well against **data-at-rest leaks and forged inbound
requests** (the likely web-app threats); the **compromised-live-host** and **Strava-availability**
risks are partially mitigated and explicitly accepted for a portfolio MVP, with the production
hardening path (envelope encryption, KMS) documented.

## What is deliberately NOT done (MVP)

- **No envelope encryption yet** — env-key MVP; KMS is the documented production step.
- **No WAF / rate-limiting on our own API** beyond the Strava-budget governance — a public-launch
  concern, not invite-only MVP.
- **No MFA / enterprise SSO** — single Google OIDC login (one identity provider; Strava is a
  connected data source, not a login, per [ADR 0011](../adr/0011-identity-provider-signup.md));
  enterprise identity would warrant Keycloak ([ADR 0009](../adr/0009-identity-spring-security.md)).
- **No immediate access-token revocation** — no per-request `jti` denylist; logout and bans leave up
  to 15 minutes of residual access ([ADR 0013](../adr/0013-access-and-refresh-tokens.md) §5).
- **No Backend-for-Frontend** — tokens reach the browser (access token in memory, refresh token in an
  `HttpOnly` cookie); a BFF is the stronger posture, deferred unless XSS-driven token theft becomes
  unacceptable ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md)).
- **No full GDPR self-service** (export/erase endpoints) — transactional deletion exists; self-service
  tooling is post-MVP.

## Related

- [encryption-at-rest.md](../technical-notes/encryption-at-rest.md) — token-column encryption mechanism
- [jwt-and-keys.md](../technical-notes/jwt-and-keys.md) — identity token + signing-key custody
- [token-management.md](../technical-notes/token-management.md) — token lifecycle, audit
- [webhooks.md](../integrations/strava/webhooks.md) — the webhook trust model
- [observability.md](observability.md) — security-signal logging/alerting
- [ADR 0003](../adr/0003-token-storage-strategy.md), [ADR 0009](../adr/0009-identity-spring-security.md),
  [ADR 0012](../adr/0012-spa-mobile-stateless-api.md), [ADR 0013](../adr/0013-access-and-refresh-tokens.md)
