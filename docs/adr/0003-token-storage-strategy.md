# ADR 0003: Strava tokens stored in backend (Postgres, AES-GCM encrypted)

- **Status:** Accepted
- **Date:** 2026-05-20
- **Reviewed:** 2026-06-14 (alternatives updated after Keycloak removal; core decision unchanged)

## Context

Aperitivo's Activity Ingestion BC needs to call Strava API endpoints **on behalf of users who are not currently online** — to handle webhook-triggered fetches, periodic reconciliation syncs, and long-running initial backfills of an athlete's history.

This means Strava access and refresh tokens must be **stored somewhere** the backend can read them at any time. The question: in which of our available components, and who manages them?

> **Review note (June 2026):** the original version of this ADR framed the main alternative as "Keycloak federation with stored tokens." Keycloak has since been removed from the stack entirely (see [ADR 0009](0009-identity-spring-security.md)), so that alternative is now moot. The decision to keep tokens in Postgres with application-level encryption is unchanged and, if anything, reinforced: with identity now also handled in-app by Spring Security, there is no identity server in the picture at all. The alternatives section below has been updated to reflect the options that remain real (Redis, Vault), with the Keycloak option retained only as historical context.

A common but mistaken intuition is "tokens are dynamic data, a relational DB is for less-dynamic data." This is not how Postgres behaves: a single-row `UPDATE` by primary key takes microseconds, and Postgres sustains thousands of such writes per second on a modest VPS. Our token-refresh rate is on the order of **single-digit writes per second even at 100K users** (an access token lives ~6 hours). The choice is therefore not about throughput; it is about encryption, audit, transactional coupling, and operational composition. On those grounds, the application database is the correct home for per-user OAuth tokens — and this matches what frameworks recommend: Spring Security's own `JdbcOAuth2AuthorizedClientService` stores OAuth tokens in a relational table.

## Decision

**Store Strava access and refresh tokens in the application PostgreSQL database**, in a `ConnectedSource` entity owned by the Identity & Access BC (see [ADR 0004](0004-user-model-and-connected-sources.md)). All token operations go through a single `TokenManager` component. Token columns are encrypted at rest using **AES-GCM**, with the key sourced from environment configuration in the MVP (production target: KMS/Vault-backed envelope encryption).

No external identity server holds the Strava tokens. Identity (our JWT) and data (Strava tokens) are separate concerns: identity is issued by Spring Security ([ADR 0009](0009-identity-spring-security.md)); data tokens live here and are used only by Activity Ingestion via `TokenManager`.

## Alternatives considered

### Redis as the primary token store

What it is: keep tokens in Redis instead of Postgres.

Why rejected as primary: tokens participate in **business transactions** — creating a `ConnectedSource`, marking it `REVOKED`, deleting a user (GDPR), etc. These must be atomic with other relational state, which Redis cannot provide. Redis also has no built-in encryption at rest (its RDB/AOF dumps would be plaintext unless we encrypt in the app anyway), and losing Redis would mean every request falls back to refreshing against Strava, quickly exhausting the rate-limit budget. Redis is, however, a good **future cache** for access tokens — see below.

### HashiCorp Vault as the primary token store

What it is: store per-user refresh tokens directly in Vault.

Why rejected: Vault and KMS are designed for a **small number of system secrets with infrequent access** (DB passwords, signing keys, the Strava client secret), not for millions of per-user records read on every webhook. Putting user tokens in Vault would put Vault on the ingestion hot path, break atomic user-plus-token operations (a Postgres transaction can't span Vault), and prevent simple queries like "which users' tokens expire within the hour." Vault *does* have a correct role here — protecting the **encryption key** via envelope encryption — but that is a different concern from storing the tokens themselves.

### Keycloak federation with stored tokens (historical)

The original main alternative: configure Strava as a federated IdP in Keycloak with `storeToken=true`, retrieve tokens via the broker token endpoint or Token Exchange. Rejected even before Keycloak was removed, because it put Keycloak on the data-ingestion hot path, depended on Token Exchange (a Keycloak Technology Preview), and forced the `ConnectedSource` domain lifecycle into Keycloak's `federated_identity` schema. Now fully moot: Keycloak is no longer in the stack ([ADR 0009](0009-identity-spring-security.md)).

## Consequences

**Positive:**
- Tokens are ordinary application data with a clean domain lifecycle (`ACTIVE`/`REVOKED`/`ERROR`), audit trail, and failure tracking, all in one `TokenManager`.
- Token operations are transactional with the rest of the IAM data (revocation, user deletion, multi-source extension).
- Backups naturally include the (encrypted) tokens — no second store to keep in sync.
- Multi-source extensibility (Garmin/Wahoo/…) is a matter of new provider adapters and enum values, with no identity-server changes.

**Negative:**
- We own token encryption at rest. Mistakes here have real consequences.
- A leak of the application database leaks all users' (encrypted) refresh tokens. Mitigation: strong encryption-at-rest, KMS/Vault-backed key in production, never log decrypted tokens, audit access.
- Each token read is a Postgres round-trip plus an in-app AES-GCM decrypt (microseconds). Acceptable at our scale.

**Risks:**
- **Concurrent refresh races.** Two parallel requests both see an expired access token and both attempt refresh. Mitigation: a per-user lock around the refresh path (`pg_advisory_lock` on `user_id`, or a Redis lock); if a refresh is already in progress, wait for it.
- **Encryption key compromise.** Mitigation: envelope encryption with a KMS/Vault-managed key, deferred to production hardening.
- **Undetected refresh-token revocation.** Mitigation: lazy validation on use plus optional proactive refresh; on 401 from Strava, mark `ConnectedSource` as `REVOKED` and emit `IntegrationRevoked`.

**Follow-ups:**
- **Future optimization — Redis access-token cache.** Read access tokens from Redis (TTL = token expiry), falling back to Postgres + Strava refresh on miss. Trigger to introduce it: measured token-read latency above ~10ms p99, or Postgres connection-pool pressure. Not in MVP.
- **Production hardening — envelope encryption.** Move the encryption key to Vault Transit / cloud KMS, with rotating data keys, so key rotation does not require re-encrypting all rows. Documented in the token-management technical note. Not in MVP.

## References

- [ADR 0004: User model and ConnectedSource](0004-user-model-and-connected-sources.md)
- [ADR 0009: Identity via Spring Security](0009-identity-spring-security.md)
- [Technical note: Token management](../technical-notes/token-management.md)
- [Spring Security — `JdbcOAuth2AuthorizedClientService`](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorized-clients.html)
- [OWASP Cryptographic Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)
