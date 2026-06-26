# Strava Integration: OAuth Flow

The Strava OAuth 2.0 authorization flow as Aperitivo uses it — the external protocol, its scopes
and quirks, and where each step is handled. This is the **external-system reference**: it describes
Strava's OAuth as it actually behaves. Our *handling* of it lives in IAM
([strava-oauth-login sequence](../../contexts/identity-access/diagrams/sequence/strava-oauth-login.md),
[jwt-and-keys.md](../../technical-notes/jwt-and-keys.md)); this note is the contract with Strava.

## What kind of OAuth Strava is

**Strava is OAuth 2.0, not OpenID Connect.** This single fact drives several design choices:

- There is **no ID token** and **no `/userinfo` OIDC endpoint** — to learn who the athlete is, we
  call the **data** endpoint `GET /api/v3/athlete` after the token exchange (a custom
  `OAuth2UserService` in IAM does this). Standard OIDC auto-population of the principal does not
  apply.
- There is **no discovery document** (`/.well-known/openid-configuration`) — the endpoints are
  fixed and configured explicitly.
- Identity is therefore **derived from a data call**, which is exactly why Aperitivo issues its
  **own** JWT for identity ([ADR 0009](../../adr/0009-identity-spring-security.md)) rather than
  treating a Strava token as an identity assertion.

## Endpoints

| Purpose | Endpoint |
|---|---|
| Authorization (user consent) | `https://www.strava.com/oauth/authorize` |
| Token exchange + refresh | `https://www.strava.com/oauth/token` |
| Athlete profile (identity) | `https://www.strava.com/api/v3/athlete` |
| Deauthorize | `https://www.strava.com/oauth/deauthorize` |

We are a **confidential client** — the `client_secret` lives only on our backend, and the
code-for-token exchange is server-to-server. The browser never sees the secret.

## The flow, step by step

```
1. App → Browser:  302 to /oauth/authorize?client_id&redirect_uri&scope&state&response_type=code&approval_prompt=auto
2. Browser → Strava:  user approves on Strava's consent screen
3. Strava → App:   302 to redirect_uri?code=...&scope=...&state=...
4. App → Strava:   POST /oauth/token (client_id, client_secret, code, grant_type=authorization_code)
5. Strava → App:   { access_token, refresh_token, expires_at, athlete:{...} }
6. App → Strava:   GET /api/v3/athlete  (identity — who is this?)
7. App:            provision User + ConnectedSource (encrypted tokens), mint our JWT
```

Steps 1–2 and 4 are **Spring Security framework** behaviour (we supply the `ClientRegistration`, not
a controller). Steps 6–7 are our seams. The single provisioning transaction (step 7) is detailed in
the [IAM OAuth login sequence](../../contexts/identity-access/diagrams/sequence/strava-oauth-login.md).

### The token response includes the athlete

A Strava quirk worth noting: the **token-exchange response itself** (step 5) already contains a
`athlete` summary object. So in principle the identity is available without the separate
`GET /api/v3/athlete`. We still make the explicit profile call (step 6) because it is the clean,
documented way to obtain the athlete and decouples identity-loading from the token-grant shape — but
implementers should know the athlete summary rides along on the token response too.

## Scopes

Aperitivo requests:

```
read,activity:read_all,profile:read_all
```

| Scope | Grants |
|---|---|
| `read` | public profile and basic data |
| `activity:read_all` | **all** activities including private/Followers-only — required, since an athlete's training history includes private activities |
| `profile:read_all` | full profile detail |

**Strava scope quirks:**
- Scopes are **comma-separated**, not space-separated (the OAuth spec allows either; Strava uses
  commas). Spring Security's `ClientRegistration` must be configured accordingly.
- The user can **deselect** scopes on the consent screen. The granted scopes come back on the
  callback (`scope=` param) and in the token response — we must check that `activity:read_all` was
  actually granted, not assume it. Without it, ingestion can only see public activities, which we
  treat as an incomplete connection.
- `approval_prompt=auto` (vs `force`) means a returning user who already granted consent is not
  re-prompted — the redirect round-trips silently. This is what makes "re-login" after JWT expiry a
  seamless redirect, not a fresh consent.

## Tokens

Strava's token model:

- **Access token:** short-lived, **~6 hours** (`expires_at` is an absolute epoch timestamp, not a
  duration). Used as `Authorization: Bearer` on API calls.
- **Refresh token:** long-lived, used to obtain new access tokens. **Strava generally does not
  rotate the refresh token** on refresh — but the response *may* contain a new one, so we store
  whatever comes back (never assume it's unchanged). This is documented in
  [token-management.md](../../technical-notes/token-management.md).

### Refresh

```
POST /oauth/token
  client_id, client_secret,
  grant_type=refresh_token,
  refresh_token=<stored>
→ { access_token, refresh_token, expires_at }
```

Refresh is **server-side, on demand** (lazy), inside IAM's `TokenManager.getValidAccessToken` — the
concurrency-safe details (per-user lock, re-read under lock) are in
[token-management.md](../../technical-notes/token-management.md). Refresh calls **count against the
rate-limit budget** ([api-quirks.md](api-quirks.md)) — negligible at our refresh rate, but counted.

## Deauthorization

Two ways a connection ends, both converging on IAM marking the `ConnectedSource` `REVOKED`:

1. **We deauthorize** — `POST /oauth/deauthorize` with the access token (e.g. user disconnects in
   our UI). Strava invalidates the grant.
2. **The user deauthorizes on Strava's side** — Strava sends a **webhook** (`object_type=athlete,
   updates.authorized=false`), handled in Ingestion and routed to IAM. See
   [webhooks.md](webhooks.md).

Either way, the tokens we hold become invalid; a subsequent API call returns `401`, which is the
other revocation trigger ([token-management.md](../../technical-notes/token-management.md)).

## Error cases at the OAuth boundary

| Case | Strava behaviour | Our handling |
|---|---|---|
| User denies consent | redirect with `error=access_denied` | Spring Security failure handler → login page with error; nothing provisioned |
| `activity:read_all` not granted | callback `scope=` omits it | treat as incomplete connection; prompt to reconnect with full scope |
| Code exchange fails | `400` from `/oauth/token` | login fails; no `User`/`ConnectedSource`, no event |
| Refresh-token rejected | `400 invalid_grant` on refresh | mark `ConnectedSource` `REVOKED`, emit `IntegrationRevoked` |

Because provisioning is **one transaction**, a failure anywhere in the post-callback steps rolls back
the whole thing — including the `event_publication` rows — so no event is ever delivered for a login
that didn't fully succeed (the Outbox property, see
[idempotency-and-outbox.md](../../technical-notes/idempotency-and-outbox.md)).

## Configuration summary (Spring Security `ClientRegistration`)

```yaml
spring.security.oauth2.client.registration.strava:
  client-id:     ${STRAVA_CLIENT_ID}
  client-secret: ${STRAVA_CLIENT_SECRET}
  authorization-grant-type: authorization_code
  redirect-uri: "{baseUrl}/login/oauth2/code/strava"
  scope: read,activity:read_all,profile:read_all
spring.security.oauth2.client.provider.strava:
  authorization-uri: https://www.strava.com/oauth/authorize
  token-uri:         https://www.strava.com/oauth/token
  user-info-uri:     https://www.strava.com/api/v3/athlete
  user-name-attribute: id
```

The `user-info-uri` + `user-name-attribute: id` is how the custom `OAuth2UserService` knows where to
load the athlete and which field is the principal name (Strava's numeric athlete `id`) — though our
internal identity is our own UUID, never this id ([jwt-and-keys.md](../../technical-notes/jwt-and-keys.md)).

## Related

- [IAM OAuth login sequence](../../contexts/identity-access/diagrams/sequence/strava-oauth-login.md)
- [token-management.md](../../technical-notes/token-management.md) — refresh, revocation, concurrency
- [jwt-and-keys.md](../../technical-notes/jwt-and-keys.md) — the identity JWT we issue after this flow
- [api-quirks.md](api-quirks.md) — rate limits that refresh calls count against
- [webhooks.md](webhooks.md) — deauthorization-as-webhook
- [ADR 0009](../../adr/0009-identity-spring-security.md), [ADR 0003](../../adr/0003-token-storage-strategy.md)
