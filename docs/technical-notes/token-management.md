# Technical Note: Token Management

How Aperitivo stores, reads, refreshes, and revokes Strava OAuth tokens. Complements [ADR 0003](../adr/0003-token-storage-strategy.md) (the decision) with the *how*.

## Where tokens live

Strava access and refresh tokens live in the `connected_sources` table (Identity & Access BC), in `access_token_encrypted` and `refresh_token_encrypted` columns (`bytea`), encrypted with **AES-GCM** at the application layer. Plaintext tokens exist only transiently in memory during a Strava API call.

Why the application database rather than a cache or a secrets manager: tokens are per-user records that participate in business transactions (creating a connection, marking it revoked, deleting a user for GDPR). They must be atomic with that relational state, queryable ("whose tokens expire within the hour"), and included in normal backups. This matches Spring Security's own `JdbcOAuth2AuthorizedClientService` approach. See [ADR 0003](../adr/0003-token-storage-strategy.md) for the full rationale, including why Redis-as-primary and Vault-as-primary were rejected.

## The `TokenManager` component

`TokenManager` is the **only** code permitted to touch encrypted token storage. Its public surface (in-process, called by Activity Ingestion):

```
StravaToken getValidAccessToken(UUID userId)
void markRevoked(UUID userId, RevocationReason reason)
void recordSuccessfulUse(UUID userId)
void recordFailure(UUID userId, FailureKind kind)
```

### `getValidAccessToken` — the hot path

1. Load the `ConnectedSource` for `(userId, STRAVA)`.
2. If `status != ACTIVE`, throw `IntegrationNotActiveException`.
3. If `access_token_expires_at` is comfortably in the future (e.g. > now + 1 min skew), decrypt and return the access token.
4. Otherwise refresh (below), under a per-user lock.

### Refresh, with concurrency control

Two concurrent webhooks for the same user can both observe an expired access token and both try to refresh. Strava issues a new access token per refresh; uncoordinated double refresh causes one call to fail and wastes rate-limit budget.

Guard the refresh path with a **per-user lock**:
- `pg_advisory_xact_lock(hashtext(user_id))` within the refresh transaction, or
- a Redis lock (`SET key NX PX`) if we prefer to keep locks out of Postgres.

The flow under lock:
1. Re-read the row (another thread may have just refreshed — if so, return the fresh token).
2. Call Strava `POST /oauth/token` with `grant_type=refresh_token`.
3. Persist the new access token, its new `expires_at`, and the (possibly unchanged) refresh token. Strava generally does not rotate refresh tokens, but we store whatever it returns.
4. Update `last_refreshed_at`; release the lock.

### Lazy vs proactive refresh

MVP uses **lazy** refresh (refresh on demand inside `getValidAccessToken`). A **proactive** background job that refreshes tokens expiring soon is an optional later optimization: it removes refresh latency from the webhook hot path and surfaces refresh failures earlier. Not required for MVP.

## Revocation

Two triggers:
1. **Strava returns 401 / invalid_token** on any API call → Ingestion calls `markRevoked`.
2. **Strava deauthorization webhook** (`aspect_type=update, updates.authorized=false`) → IAM handles it directly.

`markRevoked` sets `status = REVOKED`, clears or retains tokens per policy, and the service emits `IntegrationRevoked`. Downstream: Ingestion cancels pending sync jobs; Notifications tells the user to reconnect.

## Encryption details

- **Algorithm:** AES-GCM (authenticated encryption — integrity + confidentiality).
- **Per-value nonce:** a fresh random 96-bit nonce per encryption, stored alongside the ciphertext.
- **Implementation:** a JPA `AttributeConverter` (`EncryptedStringConverter`) so encryption/decryption is transparent to the rest of the code, or explicit encrypt/decrypt in the repository if we prefer to keep it visible.
- **Never logged.** Decrypted tokens never appear in logs, OTel spans, or error messages. Access is auditable (structured log: "token used for user X, operation Y").

### Key management

- **MVP:** a single AES key from an environment variable / mounted secret. Acceptable as a starting point.
- **Production hardening (post-MVP):** **envelope encryption**. A data encryption key (DEK) encrypts the token columns; the DEK is itself encrypted by a key encryption key (KEK) held in **Vault Transit** or a cloud **KMS**. The app authenticates to Vault/KMS (AppRole / workload identity) and never holds the KEK. Benefits: key rotation without re-encrypting all rows (rotate the KEK, re-wrap the DEK), and an audit trail of key usage. This is a strong production-grade pattern and a deliberate future learning target — it is **not** in the MVP.

## Observability

- Metrics: refresh count, refresh failures, refresh latency, count of `ACTIVE`/`REVOKED`/`ERROR` connected sources, tokens-expiring-soon gauge.
- Traces: a span around each Strava `/oauth/token` call, correlated with the triggering webhook via `trace_id`.
- Alerts: a spike in refresh failures across many users (possible Strava-side issue or a clock/skew bug) — see the `token-refresh-failures` runbook.

## Related

- [ADR 0003: Token storage strategy](../adr/0003-token-storage-strategy.md)
- [ADR 0004: User model and ConnectedSource](../adr/0004-user-model-and-connected-sources.md)
- [Identity & Access BC](../contexts/identity-access/README.md)
