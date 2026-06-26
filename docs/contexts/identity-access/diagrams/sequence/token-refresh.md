# Sequence: Token Refresh

The `TokenManager.getValidAccessToken` hot path — how Activity Ingestion gets a valid Strava access
token, including the lazy refresh under a per-user lock and the `401`-triggered revocation branch.
This is the flow that every webhook-driven and backfill activity fetch depends on.

Mechanism: [token-management.md](../../../../technical-notes/token-management.md). Contract:
[IAM domain-model](../../domain-model.md). The other revocation path (deauth webhook) is in
[revocation-flow.md](revocation-flow.md).

## What this diagram shows

1. **The fast path** — token comfortably valid, decrypt and return, no Strava call.
2. **Lazy refresh under a per-user lock** — only refresh when expired, and serialize concurrent
   refreshes so two webhooks for one user don't double-refresh.
3. **The double-check under lock** — re-read after acquiring the lock; another thread may have just
   refreshed.
4. **The `401` branch** — a rejected token routes to revocation, emitting `IntegrationRevoked`.

## Diagram

```mermaid
sequenceDiagram
    autonumber
    participant ING as Activity Ingestion<br/>(SyncJobWorker)
    participant TM as TokenManager
    participant CSR as connected_sources
    participant LK as per-user lock<br/>(pg_advisory / Redis)
    participant ST as Strava /oauth/token
    participant CSS as ConnectedSourceService
    participant EP as event_publication

    ING->>TM: getValidAccessToken(userId)
    TM->>CSR: load ConnectedSource(userId, STRAVA)

    alt status != ACTIVE
        TM-->>ING: throw IntegrationNotActiveException
    else access token valid (expires_at > now + skew)
        Note over TM: fast path — no Strava call
        TM->>TM: decrypt access_token (AES-GCM, in memory only)
        TM-->>ING: StravaToken
    else expired → refresh under lock
        TM->>LK: acquire per-user lock (hashtext(userId))
        Note over LK: serializes concurrent refreshes for the same user

        TM->>CSR: RE-READ row (another thread may have just refreshed)
        alt now valid (someone else refreshed)
            TM->>TM: decrypt fresh token
            TM->>LK: release lock
            TM-->>ING: StravaToken (no Strava call needed)
        else still expired — we refresh
            TM->>ST: POST grant_type=refresh_token (refresh_token, client_id, client_secret)
            alt 200 OK
                ST-->>TM: { access_token, refresh_token?, expires_at }
                TM->>CSR: persist new access_token (AES-GCM), expires_at,<br/>refresh_token (whatever returned), last_refreshed_at
                TM->>LK: release lock
                TM-->>ING: StravaToken
            else 400 invalid_grant (refresh token rejected)
                ST-->>TM: 400
                TM->>LK: release lock
                TM->>CSS: markRevoked(userId, TOKEN_REJECTED)
                CSS->>CSR: status = REVOKED
                CSS->>EP: publish IntegrationRevokedEvent{reason=TOKEN_REJECTED}
                TM-->>ING: throw IntegrationNotActiveException
                Note over EP: → Ingestion cancels jobs; Notifications tells user to reconnect
            else timeout / 5xx (Strava-side)
                ST-->>TM: error
                TM->>LK: release lock
                TM->>CSS: recordFailure(userId, REFRESH_ERROR)
                TM-->>ING: throw (job retries with backoff; token not revoked)
                Note over TM: not a revocation — existing access tokens still valid<br/>until expiry; see token-refresh-failures runbook
            end
        end
    end
```

## Notes keyed to the locked decisions

- **Fast path is the common case.** An access token lives ~6 hours; most calls hit the "valid →
  decrypt → return" branch with no Strava round-trip and no lock. Refresh is the exception, not the
  rule.
- **The per-user lock prevents double refresh.** Two concurrent webhooks for one user could both see
  an expired token; without the lock, both refresh, one wastes a rate-limit call and may get a stale
  result. `pg_advisory_xact_lock(hashtext(user_id))` or a Redis `SET NX PX` serializes them
  ([token-management.md](../../../../technical-notes/token-management.md)).
- **Re-read under lock is the double-checked-locking pattern.** The thread that loses the race
  acquires the lock *after* the winner already refreshed — it must re-read and use the fresh token,
  not refresh again. This is why step "RE-READ row" exists.
- **`400 invalid_grant` is a real revocation** — the refresh token itself is dead, so the source goes
  `REVOKED(TOKEN_REJECTED)` and `IntegrationRevoked` fires. Contrast a `5xx`/timeout, which is
  **transient** — the job retries, the token is *not* revoked, and existing access tokens keep
  working until expiry (the distinction the `token-refresh-failures` runbook leans on).
- **Tokens are plaintext only in memory, only at use.** Decryption happens just before returning to
  Ingestion; the columns are always AES-GCM ciphertext ([encryption-at-rest.md](../../../../technical-notes/encryption-at-rest.md)).
- **Refresh counts against the rate-limit budget** — negligible at our refresh rate, but the limiter
  counts it ([strava-rate-limits.md](../../../../technical-notes/strava-rate-limits.md)).
```
