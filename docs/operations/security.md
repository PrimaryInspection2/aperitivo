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
3. **The Strava client secret** — a leak compromises the whole app's Strava integration.
4. **User data** (workouts, analytics) — lower sensitivity but still private.

The data we *don't* heavily protect (workout summaries, analytics) is a deliberate scoping: encrypt
credentials and secrets, keep domain data queryable
([encryption-at-rest.md](../technical-notes/encryption-at-rest.md)).

## Attack surface

| Surface | Exposure | Primary defense |
|---|---|---|
| Strava OAuth callback (`/login/oauth2/code/strava`) | public | Spring Security framework, `state` CSRF guard |
| Webhook receiver (`POST /api/webhooks/strava`) | public | `subscription_id` filter + authenticated re-fetch as the real trust boundary |
| Webhook validation (`GET …`) | public | `hub.verify_token` match |
| Authenticated API (`/api/**`) | public, JWT-gated | RS256 JWT validation per resource-server BC |
| SSE stream (`/api/sse/events`) | public, JWT-gated | JWT validation; per-user emitter scoping |
| Admin/ops endpoints (Actuator) | internal | network-restricted, not public |

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

- **Authentication:** our RS256 JWT, validated by every BC as a Spring Security resource server.
  `sub` = internal `user_id`. Stateless; optional Redis `jti` denylist for immediate logout
  ([jwt-and-keys.md](../technical-notes/jwt-and-keys.md)).
- **Authorization:** MVP is single-user-type — every authenticated user accesses **only their own**
  data, enforced by scoping every query to the JWT's `user_id` (never a client-supplied id). There
  is no role model yet; when one arrives it is an additive JWT claim.
- **Ownership checks:** endpoints return `404` (not `403`) for resources not owned by the caller, so
  existence of other users' data isn't leaked (documented per BC API).

## Encryption

- **In transit:** HTTPS everywhere (TLS terminated at nginx); the SSE stream and webhook callback are
  HTTPS. Strava requires HTTPS callback URLs.
- **At rest:** AES-GCM column encryption for token columns; the rest of the DB relies on
  deployment-level disk encryption if present ([encryption-at-rest.md](../technical-notes/encryption-at-rest.md)).

## Audit logging

- **Token access is audited** — every use of a decrypted token logs the *fact* (user, operation,
  `trace_id`), never the value ([token-management.md](../technical-notes/token-management.md)).
- **Security-significant transitions** logged at WARN: source REVOKED, JWT validation failures
  (by reason), repeated 401s from Strava.
- **Logs never contain secrets** — enforced as a policy and a review item
  ([observability.md](observability.md)).

## GDPR / data lifecycle (MVP posture)

- **User deletion** is transactional — because tokens and user data live in the same Postgres,
  deleting a user cascades through their `ConnectedSource` (tokens), and the relevant per-BC data by
  `user_id`. (A full GDPR export/erase endpoint is post-MVP, noted in several BC APIs.)
- **Token revocation on disconnect** — disconnecting a source revokes at Strava and marks `REVOKED`;
  retained vs cleared tokens per policy ([token-management.md](../technical-notes/token-management.md)).
- **Raw payload retention** — `RawActivityPayload` is kept as replay source of truth; a retention
  policy to age it out is a documented lever, not MVP ([timescaledb-schema.md](../technical-notes/timescaledb-schema.md)).

## Threat model summary

| Threat | Defended? | By |
|---|---|---|
| DB dump leak | ✅ | token columns encrypted; useless without key/KEK |
| Forged webhook | ✅ | identifiers-only + authenticated re-fetch; worst case a wasted GET |
| Stolen JWT | ⚠️ partial | short expiry; optional `jti` denylist for immediate revoke |
| Signing-key leak | ✅ (mitigated) | env→KMS custody; rotation via JWKS `kid` overlap |
| CSRF on OAuth | ✅ | Spring Security `state` parameter |
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
- **No MFA / enterprise SSO** — single Strava-OAuth login; enterprise identity would warrant Keycloak
  ([ADR 0009](../adr/0009-identity-spring-security.md)).
- **No full GDPR self-service** (export/erase endpoints) — transactional deletion exists; self-service
  tooling is post-MVP.

## Related

- [encryption-at-rest.md](../technical-notes/encryption-at-rest.md) — token-column encryption mechanism
- [jwt-and-keys.md](../technical-notes/jwt-and-keys.md) — identity token + signing-key custody
- [token-management.md](../technical-notes/token-management.md) — token lifecycle, audit
- [webhooks.md](../integrations/strava/webhooks.md) — the webhook trust model
- [observability.md](observability.md) — security-signal logging/alerting
- [ADR 0003](../adr/0003-token-storage-strategy.md), [ADR 0009](../adr/0009-identity-spring-security.md)
