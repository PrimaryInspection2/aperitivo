# Strava Integration: OAuth Flow

The Strava OAuth 2.0 authorization flow as Aperitivo uses it — the external protocol, its scopes
and quirks, and where each step is handled. This is the **external-system reference**: it describes
Strava's OAuth as it actually behaves. Our *handling* of it lives in IAM ([api.md](../../contexts/identity-access/api.md),
[domain-model.md](../../contexts/identity-access/domain-model.md)); this note is the contract with Strava.

**Strava is a data source that is *connected*, not a login provider.** Since
[ADR 0011](../../adr/0011-identity-provider-signup.md) the user signs in with Google (an OIDC
identity provider); the Strava OAuth flow below is a **connect** flow, initiated by an
**already-authenticated** user from inside the app (`GET /connect/strava`). It creates **no `User`**
and mints **no identity token** — the caller stays authenticated by our own JWT throughout. What it
produces is a `ConnectedSource` holding the encrypted Strava tokens, and an `IntegrationConnectedEvent`.
The Strava protocol itself — authorize URL, scopes, code exchange, token response, refresh, revocation
— is **unchanged**; what changed is *who* starts it, *when*, and at *which* endpoints.

## What kind of OAuth Strava is

**Strava is OAuth 2.0, not OpenID Connect.** This single fact drives several design choices:

- There is **no ID token** and **no `/userinfo` OIDC endpoint** — to learn *which athlete* these
  tokens belong to, we call the **data** endpoint `GET /api/v3/athlete` after the token exchange.
  In the connect flow this call is **explicit and our own**: Spring Security's `oauth2Client`
  (`OAuth2AuthorizationCodeGrantFilter`), unlike `oauth2Login`, does **not** invoke an
  `OAuth2UserService`, so nothing fetches the athlete for us — we fetch it ourselves right after the
  code exchange. (Under the old `oauth2Login` login model this happened inside a custom
  `OAuth2UserService`; that seam is gone — see [ADR 0011](../../adr/0011-identity-provider-signup.md).)
- There is **no discovery document** (`/.well-known/openid-configuration`) — the endpoints are
  fixed and configured explicitly.
- The athlete id we get back **identifies the connected source, not the user.** It is stored as
  `providerUserId` on the `ConnectedSource` and used to key that source; it is **never** the user's
  identity. Aperitivo's identity is our own JWT `sub` (an internal UUID), issued by the Google login
  flow ([ADR 0009](../../adr/0009-identity-spring-security.md),
  [ADR 0011](../../adr/0011-identity-provider-signup.md)) — a Strava token is never treated as an
  identity assertion.

## Endpoints

| Purpose | Endpoint |
|---|---|
| Authorization (user consent) | `https://www.strava.com/oauth/authorize` |
| Token exchange + refresh | `https://www.strava.com/oauth/token` |
| Athlete profile (source athlete id) | `https://www.strava.com/api/v3/athlete` |
| Revoke tokens | `https://www.strava.com/oauth/revoke` |
| Deauthorize (legacy, removed 2027-06-01) | `https://www.strava.com/oauth/deauthorize` |

We are a **confidential client** — the `client_secret` lives only on our backend, and the
code-for-token exchange is server-to-server. The browser never sees the secret.

## The flow, step by step

The flow is started by an **already-authenticated** user hitting `GET /connect/strava` (Spring
Security's `oauth2Client` authorization endpoint for the `strava` registration); the browser is then
redirected to Strava. The current `user_id` is read from the caller's JWT — the code exchange never
touches the `SecurityContext`, so the user stays logged in as themselves throughout.

```
0. User → App:     GET /connect/strava  (authenticated; app-initiated connect)
1. App → Browser:  302 to /oauth/authorize?client_id&redirect_uri&scope&state&response_type=code&approval_prompt=auto
2. Browser → Strava:  user approves on Strava's consent screen
3. Strava → App:   302 to redirect_uri (/connect/oauth2/code/strava)?code=...&scope=...&state=...
4. App → Strava:   POST /oauth/token (client_id, client_secret, code, grant_type=authorization_code)
5. Strava → App:   { access_token, refresh_token, expires_at, athlete:{...} }
6. App → Strava:   GET /api/v3/athlete  (which athlete are these tokens for? — fetched explicitly)
7. App:            linkSource(userId, athlete, tokens) → create/reactivate ConnectedSource
                   (encrypted tokens, status=ACTIVE), publish IntegrationConnectedEvent
```

Steps 1–2 and 4 are **Spring Security framework** behaviour (we supply the `ClientRegistration`, not
a controller); with `oauth2Client` the framework stores an `OAuth2AuthorizedClient` **without
authenticating anyone**. Steps 6–7 are our seams. Step 7 links the source to the **current** user —
it does **not** create a `User` and does **not** mint a JWT (the user is already authenticated). The
transaction is detailed in IAM ([api.md](../../contexts/identity-access/api.md),
[domain-model.md](../../contexts/identity-access/domain-model.md)) and grounded in
[ADR 0011](../../adr/0011-identity-provider-signup.md).

### The token response includes the athlete

A Strava quirk worth noting: the **token-exchange response itself** (step 5) already contains a
`athlete` summary object. So in principle the athlete id is available without the separate
`GET /api/v3/athlete`. We still make the explicit profile call (step 6) because it is the clean,
documented way to obtain the athlete and decouples athlete-loading from the token-grant shape — and
because, with `oauth2Client`, nothing loads it for us — but implementers should know the athlete
summary rides along on the token response too.

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
  re-prompted — the redirect round-trips silently. This is what makes a **reconnect** (e.g. after a
  prior revoke, when the Strava grant is still valid) a seamless redirect rather than a fresh consent
  screen.

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

1. **We deauthorize** — we call Strava's revocation endpoint (e.g. the user disconnects in our UI).
   Strava invalidates the grant and removes us from the athlete's apps settings page. Which endpoint
   to call is not obvious right now — see [below](#which-revocation-endpoint).
2. **The user deauthorizes on Strava's side** — Strava sends a **webhook** (`object_type=athlete,
   updates.authorized=false`), handled in Ingestion and routed to IAM. See
   [webhooks.md](webhooks.md).

Either way, the tokens we hold become invalid; a subsequent API call returns `401`, which is the
other revocation trigger ([token-management.md](../../technical-notes/token-management.md)).

### Which revocation endpoint

Strava is mid-migration between two endpoints whose contracts are **not compatible** — different
authentication, different parameter placement, different success body:

| | `/oauth/deauthorize` (legacy) | `/oauth/revoke` (current) |
|---|---|---|
| Availability | works today; removed **2027-06-01** | documented live since **2026-06-01** — but see below |
| Authenticates as | the **athlete** (`access_token`) | the **application** (Basic `client_id:client_secret`) |
| Token passed as | `access_token` **query parameter** | `token` form field (+ optional `token_type_hint`) |
| Success | `200` `{"access_token": "..."}` | `200` with an **empty body** |

Aperitivo targets **`/oauth/revoke`**. The legacy endpoint is documented here only because most
existing tutorials still show it, and because its failure mode is genuinely confusing.

**Legacy — the token goes in the query string, not in an `Authorization` header:**

```
POST https://www.strava.com/oauth/deauthorize?access_token=<access_token>
```

Sending it as a bearer header instead does **not** match the route, and Strava responds with its
*HTML 404 website page* rather than a JSON error. Any HTML body from Strava means "no such route" —
authentication failures are always JSON with `401`.

**Current:**

```
POST https://www.strava.com/oauth/revoke
Authorization: Basic <base64(client_id + ":" + client_secret)>
Content-Type: application/x-www-form-urlencoded

token=<access_or_refresh_token>&token_type_hint=access_token
```

Revoking a refresh token also revokes its access tokens, and vice versa — so one call ends the
connection regardless of which token we happen to pass. Failure modes: `400` when `token` is
missing, `401` on a bad Basic header, `503` on an internal Strava error (**safe to retry**).

`/oauth/revoke` returns `200` **whether or not the token was found**, which makes revocation
naturally idempotent — a retry or a duplicate `IntegrationRevoked` cannot corrupt state, so no dedup
storage is needed (pattern 1 in
[idempotency-and-outbox.md](../../technical-notes/idempotency-and-outbox.md)).

> **`/oauth/revoke` does not work yet — verified 2026-08-07.** Every call returns `503`, for both
> `token_type_hint` values, with correct application Basic auth, while `GET /api/v3/athlete`
> returned `200` at the same moment (so this is not a Strava-wide outage). Strava documents `503`
> here as a retryable internal error, but it is not transient — it is the only response the endpoint
> gives. **Until that changes, revocation must use `/oauth/deauthorize`**, which works and kills the
> access *and* refresh token together. Re-test before building on `/oauth/revoke`; the migration has
> to land before **2027-06-01**.

## Error cases at the OAuth boundary

| Case | Strava behaviour | Our handling |
|---|---|---|
| User denies consent | redirect with `error=access_denied` | Spring Security failure handler → back to the app (connect/settings) with an error; nothing linked |
| `activity:read_all` not granted | callback `scope=` omits it | treat as incomplete connection; prompt to reconnect with full scope |
| Code exchange fails | `400` from `/oauth/token` | connect fails; no `ConnectedSource`, no event (the `User` is untouched — it already exists) |
| Refresh-token rejected | `400` + `{"errors":[{"resource":"RefreshToken","code":"invalid"}]}` | mark `ConnectedSource` `REVOKED`, emit `IntegrationRevoked` |

**Strava does not use standard OAuth 2.0 error bodies on these.** There is no `error=invalid_grant`
field — errors come back in Strava's own envelope, e.g. a dead refresh token (verified 2026-08-07):

```json
{"message":"Bad Request","errors":[{"resource":"RefreshToken","field":"refresh_token","code":"invalid"}]}
```

and a dead access token on a data call, which is `401`:

```json
{"message":"Authorization Error","errors":[{"resource":"Athlete","field":"access_token","code":"invalid"}]}
```

So revocation detection must key on **HTTP status + this envelope**, never on an `invalid_grant`
string — that field does not exist on the wire. The consumers of this rule are the
[token-refresh sequence](../../contexts/identity-access/diagrams/sequence/token-refresh.md) and the
[token-refresh-failures runbook](../../operations/runbooks/token-refresh-failures.md).

Because linking is **one transaction**, a failure anywhere in the post-callback steps rolls back
the whole thing — including the `event_publication` rows — so no event is ever delivered for a
connect that didn't fully succeed (the Outbox property, see
[idempotency-and-outbox.md](../../technical-notes/idempotency-and-outbox.md)).

## Configuration summary (Spring Security `ClientRegistration`)

Strava is registered as an `oauth2Client` (connect), **not** an `oauth2Login` (login) registration —
the redirect URI lives under `/connect/**`, which is what routes the callback through
`OAuth2AuthorizationCodeGrantFilter` instead of the login filter ([ADR 0011](../../adr/0011-identity-provider-signup.md)):

```yaml
spring.security.oauth2.client.registration.strava:
  client-id:     ${STRAVA_CLIENT_ID}
  client-secret: ${STRAVA_CLIENT_SECRET}
  authorization-grant-type: authorization_code
  redirect-uri: "{baseUrl}/connect/oauth2/code/strava"
  scope: read,activity:read_all,profile:read_all
spring.security.oauth2.client.provider.strava:
  authorization-uri: https://www.strava.com/oauth/authorize
  token-uri:         https://www.strava.com/oauth/token
  user-info-uri:     https://www.strava.com/api/v3/athlete
```

There is **no `OAuth2UserService`** in the connect flow, so `user-info-uri` / `user-name-attribute`
are **not** framework-consumed the way they are under `oauth2Login`: `oauth2Client` never calls a
user-info service. We fetch `GET /api/v3/athlete` **ourselves** after the code exchange to obtain the
Strava athlete `id` that keys the `ConnectedSource` — our internal identity is our own UUID, never
this id, and the identity JWT is minted by the separate Google login flow
([jwt-and-keys.md](../../technical-notes/jwt-and-keys.md)).

## Related

- [IAM api.md](../../contexts/identity-access/api.md) — the `/connect/**` and `/api/me/sources` surface that drives this flow
- [IAM domain-model.md](../../contexts/identity-access/domain-model.md) — `linkSource`, `ConnectedSource`, the `sub = user_id` rule
- [token-management.md](../../technical-notes/token-management.md) — refresh, revocation, concurrency
- [jwt-and-keys.md](../../technical-notes/jwt-and-keys.md) — the identity JWT (issued by the Google login flow, not this connect flow)
- [api-quirks.md](api-quirks.md) — rate limits that refresh calls count against
- [webhooks.md](webhooks.md) — deauthorization-as-webhook
- [ADR 0011](../../adr/0011-identity-provider-signup.md) — Strava demoted to a connected data source; login is Google
- [ADR 0009](../../adr/0009-identity-spring-security.md), [ADR 0003](../../adr/0003-token-storage-strategy.md)
