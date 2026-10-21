# ADR 0013: Short-lived access tokens with rotating refresh tokens

- **Status:** Accepted
- **Date:** 2026-10-16
- **Related:** [ADR 0009](0009-identity-spring-security.md) (own RS256 issuer), [ADR 0012](0012-spa-mobile-stateless-api.md) (the login that issues these tokens and the client topology they serve)

## Context

We issue our own JWTs ([ADR 0009](0009-identity-spring-security.md)). The simplest lifecycle is a single token with a long lifetime (24–72h) and **no refresh token**: on expiry the user signs in through Google again, and logout either just clears the client's copy (a stolen copy stays valid until `exp`) or adds the token's `jti` to a Redis denylist that **every protected request** checks.

For our clients, a SPA and a mobile app ([ADR 0012](0012-spa-mobile-stateless-api.md)), that trade-off is unacceptable:

- **Re-login every one to three days** is poor UX on the web and unacceptable on mobile.
- **A stolen long-lived bearer token is valid for days.** The only mitigation, the denylist, puts a Redis lookup on every request, so the API is no longer stateless in the sense that matters.
- **The SPA holds the access token in memory** (never `localStorage`, out of XSS reach between page loads). It loses it on every page reload and needs a way to get a new one without a Google round-trip.

## Decision

**Two tokens with different jobs: a 15-minute access JWT, validated by signature alone, and an opaque, rotating, server-stored refresh token, used only to obtain new access tokens.**

### 1. Access token

- An RS256 JWT minted by our `JwtEncoder` ([jwt-and-keys.md](../technical-notes/jwt-and-keys.md) for keys and `kid`).
- **TTL: 15 minutes.**
- **Claims:** `sub` = internal `user_id` (UUID), `iss`, `aud` = `aperitivo-api`, `iat`, `exp`, `jti`. **Nothing else.** No email, name or connected sources. A JWT is signed, not encrypted, so anyone holding it can read it; its claims are frozen until `exp`; and it travels with every request. Profile data comes from `GET /api/me`.
- Returned in the JSON body of `/api/auth/google` and `/api/auth/refresh` (`{ "accessToken", "tokenType": "Bearer", "expiresIn": 900 }`) and sent as `Authorization: Bearer`. The SPA keeps it in a variable.
- Validated by the resource server **by signature, `iss`, `aud` and `exp` only**, with no database or Redis lookup.

### 2. Refresh token

- **Opaque:** 256 random bits, base64url-encoded. It is not a JWT. Nothing in it needs to be self-describing, and revocation requires a server record anyway.
- **Stored as a SHA-256 hash**, never in plaintext, in Postgres:

  ```
  refresh_tokens(id, token_hash UNIQUE, user_id, family_id, expires_at,
                 used_at NULL, revoked_at NULL, created_at)
  ```

- **Transport:**
  - **Web:** cookie `refresh_token`; `HttpOnly; Secure; SameSite=Strict; Path=/api/auth`. No script can read it, and the browser sends it only to `/api/auth/*` from our own site.
  - **Native clients (post-MVP):** response body; the app keeps it in Keychain or Keystore. The exact selection mechanism is specified when a native client exists.
- **Lifetimes (initial values, to be tuned):** each refresh token expires after 14 days of inactivity. A family (one sign-in on one device) has an **absolute lifetime of 30 days**, after which the user signs in through Google again.

### 3. Rotation and reuse detection

Each `POST /api/auth/refresh`:

1. looks up the presented token by hash and checks that it is unexpired, unused and not revoked;
2. marks it `used_at = now`;
3. issues a **new** refresh token in the **same family** and a new access token.

**Presenting an already-used token** means two parties hold the same token. The server cannot tell which is the legitimate user, so it **revokes the whole family**. Both must sign in again, and only the legitimate user can. This follows [RFC 9700 §4.14](https://www.rfc-editor.org/rfc/rfc9700#section-4.14) (refresh-token rotation for public clients).

Two tabs refreshing at the same moment would otherwise look like reuse. Two mitigations:
- a **grace window** of 10–30 s, during which the just-rotated token is still accepted;
- a **single in-flight refresh** shared across tabs in the SPA.

### 4. Logout

- `POST /api/auth/logout` revokes the family of the presented refresh token and clears the cookie (`Max-Age=0` with the same `Path` and attributes). It is idempotent and identifies the session by the refresh cookie, so it works even when the access token has expired.
- The client discards the in-memory access token and calls `google.accounts.id.disableAutoSelect()`, so that Google One Tap does not sign the user straight back in.
- **The access token stays valid for at most 15 minutes after logout.** This is accepted: logout ends a session, it is not a response to theft, which rotation and family revocation handle.
- Our logout does not sign the user out of Google. Signing in again is one click, which is the expected single sign-on behaviour.
- **"Log out everywhere"** revokes every family of the user.

### 5. No per-request denylist in the MVP

No `jti` denylist is checked on requests. The `jti` claim is still minted, keeping open a future "revoke now" for bans and forced logout: either a short-lived denylist or a per-user "tokens issued before T are invalid" watermark.

### 6. Postgres, not Redis, for refresh tokens

- **Durability:** a Redis restart without persistence would sign every user out.
- **Relational queries** such as "all sessions of a user" (log out everywhere, a future device list).
- **The load is trivial:** about one refresh per active user every 15 minutes, roughly 110 requests per second even at 100,000 concurrently active users, each an indexed lookup by hash.

## Alternatives considered

- **Single long-lived JWT, no refresh.** Rejected: re-login every one to three days, a stolen token stays valid for days, and immediate revocation costs a lookup on every request.
- **Refresh token as a long-lived JWT.** Rejected: it cannot be revoked without a server-side record, at which point the JWT format adds nothing.
- **Refresh tokens without rotation (a fixed token reused until expiry).** Rejected: theft is undetectable, and a stolen token works for its whole lifetime.
- **Refresh tokens in Redis.** This is viable, but loses durability and relational queries for no measurable performance gain at our scale.
- **Server-side sessions (BFF).** Considered and deferred in [ADR 0012](0012-spa-mobile-stateless-api.md).

## Consequences

**Positive:**
- **Users stay signed in for weeks** on web and mobile without visiting Google.
- **Small theft window:** a stolen access token lives at most 15 minutes, and a stolen refresh token is detected on its next use by either party.
- **Revocation without per-request lookups.** API validation remains signature-only.
- **The refresh token never reaches JavaScript on the web,** because it is `HttpOnly` and path-scoped.

**Negative:**
- **Server-side state:** the refresh store. This is accepted deliberately. It is touched once per refresh, not on every request; "stateless API" means each API request is authenticated without a lookup.
- **More code:** a refresh-token service, a migration, rotation and family logic, and a job that deletes expired rows.
- **Concurrency edge cases** (several tabs, flaky networks) need the grace window and client-side single-flight to avoid spurious logouts.
- **Up to 15 minutes of residual access** after logout or a ban.

**Risks:**
- **Database leak.** Mitigation: only hashes are stored; a hash cannot be presented as a token.
- **Clock skew between instances** affects `exp`. Mitigation: NTP, and the default clock-skew tolerance of the JWT validators.
- **Unbounded table growth.** Mitigation: periodic deletion of rows past `expires_at`.

**Follow-ups:**
- Code: an `AccessTokenIssuer` (`JwtEncoder`), a `RefreshTokenService`, the `refresh_tokens` Flyway migration, and a cleanup job. Tracked in [plans/identity.md](../plans/identity.md).

## References

- [ADR 0009: Identity in-app with Spring Security and our own JWT issuer](0009-identity-spring-security.md)
- [ADR 0012: SPA and mobile clients; the backend is a stateless API with ID-token login](0012-spa-mobile-stateless-api.md)
- [RFC 9700 — Best Current Practice for OAuth 2.0 Security, §2.2.2 and §4.14 (refresh tokens)](https://www.rfc-editor.org/rfc/rfc9700)
- [RFC 6749 §1.5 — Refresh Token](https://www.rfc-editor.org/rfc/rfc6749#section-1.5)
- [jwt-and-keys.md](../technical-notes/jwt-and-keys.md)
