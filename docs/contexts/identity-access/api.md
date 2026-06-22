# Identity & Access — API

The HTTP surface of the IAM Bounded Context: the Strava login flow, the current-user endpoint, and logout. Most of the "login" surface is **provided by Spring Security**, not hand-written controllers — this document is explicit about which parts are framework configuration and which are our code.

Authentication model, JWT structure, and the OAuth decision: [ADR 0009](../../adr/0009-identity-spring-security.md). Entities and service seams: [domain-model.md](domain-model.md). Events fired during login: [events.md](events.md).

## Endpoint surface

| Method & path | Owner | Auth | Purpose |
|---|---|---|---|
| `GET /oauth2/authorization/strava` | Spring Security (framework) | none | Initiate Strava OAuth — redirect to Strava's authorize URL |
| `GET /login/oauth2/code/strava` | Spring Security (framework) + our seams | none (the OAuth `state` guards it) | Strava redirect/callback — code exchange, provisioning, JWT issuance |
| `GET /api/me` | our controller | required | Current authenticated user |
| `POST /api/auth/logout` | our controller | required | Invalidate the session (clear cookie, blacklist `jti`) — optional in MVP |

There is **no** signup/registration endpoint: provisioning happens implicitly inside the OAuth callback on first login. There is **no** REST endpoint for Strava tokens — those are accessed only in-process via `TokenManager`. The Strava **deauthorization webhook** is **not** here; it is received by Activity Ingestion's `WebhookController` and routed back to `ConnectedSourceService.markRevoked` (see the "two channels" section of [domain-model.md](domain-model.md)).

---

## Authentication model

**One token, two transports.** Identity is our self-issued RS256 JWT (claims: `sub` = internal `user_id` UUID, `iss`, `iat`, `exp` 24–72h, `jti` — see [domain-model.md](domain-model.md)). The same token is delivered and accepted two ways:

- **Browser (Thymeleaf + HTMX demo):** the JWT rides in an **HttpOnly cookie**. The browser attaches it automatically; no JavaScript reads or stores the token. This is the transport the built-in demo UI uses.
- **API clients (canonical):** the JWT is sent as `Authorization: Bearer <jwt>`. This is the documented, canonical way to authenticate against the API.

The application is a Spring Security **resource server**: every protected request is authenticated by validating the JWT against our public key (`JwtDecoder`). A custom `BearerTokenResolver` makes the two transports interchangeable — it reads the `Authorization` header first and falls back to the cookie:

```java
// Illustrative — prefer the Authorization header, fall back to the auth cookie.
class CookieOrHeaderBearerTokenResolver implements BearerTokenResolver {
    private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        String fromHeader = delegate.resolve(request);     // Authorization: Bearer …
        if (fromHeader != null) return fromHeader;
        return readCookie(request, "token");               // HttpOnly auth cookie
    }
}
```

**Cookie attributes:** `HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age = token TTL`. `HttpOnly` keeps it out of reach of any script (XSS cannot read it); `SameSite=Lax` blocks it from cross-site POSTs; `Secure` confines it to HTTPS.

**CSRF.** Because the cookie is sent automatically by the browser, state-changing requests need CSRF protection (Bearer-header requests from API clients do not — there is no ambient credential to forge). Spring Security's built-in CSRF support is enabled; Thymeleaf injects the CSRF token into rendered forms automatically, and HTMX is configured once to send the token as a request header. Read-only endpoints (`GET /api/me`) are unaffected.

```java
// Illustrative resource-server + login wiring.
http
    .oauth2Login(oauth -> oauth
        .userInfoEndpoint(ui -> ui.userService(stravaOAuth2UserService))
        .successHandler(jwtIssuingSuccessHandler))      // mint JWT, set cookie, redirect
    .oauth2ResourceServer(rs -> rs
        .bearerTokenResolver(cookieOrHeaderBearerTokenResolver)
        .jwt(Customizer.withDefaults()))                // validate our RS256 JWT
    .csrf(Customizer.withDefaults());
```

---

## The login flow (which parts are ours)

```mermaid
sequenceDiagram
    participant B as Browser
    participant SS as Spring Security
    participant Strava
    participant IAM as IAM services

    B->>SS: GET /oauth2/authorization/strava
    SS->>Strava: redirect to authorize (client_id, scopes, state)
    Strava-->>B: user approves
    Strava->>SS: GET /login/oauth2/code/strava?code&state
    SS->>Strava: exchange code → access+refresh tokens
    SS->>IAM: OAuth2UserService: load GET /api/v3/athlete
    SS->>IAM: success handler → StravaOAuthService.handleCallback(...)
    Note over IAM: resolve/create User, create ConnectedSource (encrypted),<br/>publish UserRegistered + IntegrationConnected
    IAM-->>SS: UserEntity
    SS-->>B: Set-Cookie: token=<JWT>; redirect to /
```

### `GET /oauth2/authorization/strava` — initiate

Pure framework. Spring Security redirects to Strava's authorize endpoint with our `client_id`, the requested scopes (`read,activity:read_all,profile:read_all`), and a generated `state` (the OAuth-flow CSRF guard, handled by the framework). We write no controller; we only supply the `ClientRegistration` (client id/secret, authorize/token/redirect URIs, scopes) in configuration. We are a **confidential** client — the `client_secret` is held server-side and the code-for-token exchange happens server-to-server.

### `GET /login/oauth2/code/strava` — callback

Strava redirects the browser here with `?code&state`. Spring Security validates `state`, exchanges the `code` for Strava access + refresh tokens, and lands them in an `OAuth2AuthorizedClient`. Two seams of our code then run:

1. **`OAuth2UserService` (custom).** Strava is OAuth 2.0, not OIDC, so there is no ID token; we load the athlete from Strava's `GET /api/v3/athlete` to obtain the profile (`athleteId`, display name, avatar, email if scoped). This becomes the `StravaProfile`.

2. **`AuthenticationSuccessHandler` (custom).** Pulls the Strava tokens out of the `OAuth2AuthorizedClient`, assembles a `StravaOAuthResult` (`StravaProfile` + `StravaTokens`), and calls `StravaOAuthService.handleCallback(...)`. That service resolves or creates the `UserEntity`, creates the `ConnectedSourceEntity` with **AES-GCM-encrypted** tokens, and publishes `UserRegisteredEvent` (first login only) + `IntegrationConnectedEvent`. The handler then mints our JWT for the returned user, sets it as the HttpOnly cookie, and redirects to the app home.

   > We do **not** use Spring Security's `OAuth2AuthorizedClientService` as the durable token store. Strava tokens live in our `ConnectedSource` (owned by IAM, accessed only via `TokenManager`) — see [ADR 0003](../../adr/0003-token-storage-strategy.md). The `OAuth2AuthorizedClient` is just the framework's transient carrier from which we extract the tokens once.

**Failure handling.** If the user denies access on Strava, or the code exchange fails, Spring Security invokes a failure handler that redirects to the login page with an error indication. No `User` or `ConnectedSource` is created; no event is emitted.

---

## `GET /api/me`

Returns the authenticated user. Auth required (cookie or Bearer); `401` otherwise.

**Response `200`:**

```json
{
  "id": "3a9e2c7e-…",
  "displayName": "Andrii K.",
  "email": "andrii@example.com",
  "avatarUrl": "https://…/avatar.jpg",
  "connection": {
    "provider": "STRAVA",
    "status": "ACTIVE"
  },
  "createdAt": "2026-06-01T10:00:00Z"
}
```

```java
public record UserDto(
        UUID         id,
        String       displayName,
        String       email,         // nullable — Strava may not expose it
        String       avatarUrl,     // nullable
        ConnectionDto connection,   // the user's STRAVA source summary
        Instant      createdAt
) {}

public record ConnectionDto(
        Provider     provider,      // STRAVA in MVP
        SourceStatus status         // ACTIVE | REVOKED | ERROR
) {}
```

`connection` carries only IAM-owned health (`provider`, `status`) — enough for the UI to show "connected" vs "reconnect needed". It deliberately does **not** carry ingestion/backfill progress ("imported 124 of 540") — that is Activity Ingestion's concern, surfaced via SSE, not here. The `user_id` is resolved from the JWT `sub`; the endpoint never trusts a client-supplied id.

---

## `POST /api/auth/logout` (optional in MVP)

Invalidates the current session: clears the auth cookie (`Set-Cookie: token=; Max-Age=0`) and adds the token's `jti` to a Redis blacklist with TTL equal to the token's remaining lifetime, so a still-valid Bearer copy cannot be reused. Returns `204`.

This is **optional** for the MVP: with short-lived tokens, logout can be reduced to clearing the cookie (the Bearer path simply lets the token expire), and the `jti` blacklist can be deferred. See [ADR 0009](../../adr/0009-identity-spring-security.md) and the JWT technical note.

---

## Errors

| Status | When |
|---|---|
| `401 Unauthorized` | No token, expired token, bad signature, or a blacklisted `jti` on a protected endpoint |
| `403 Forbidden` | Valid token but a missing/invalid CSRF token on a cookie-authenticated mutating request |
| `302 Found` | OAuth redirects (initiate → Strava; callback → app home on success, login page on failure) |

Error bodies for the JSON API follow the project-wide problem format (RFC 7807 `application/problem+json`); the OAuth endpoints redirect rather than return JSON, since they are browser-driven.

---

## What is intentionally NOT in this API (MVP)

- **No self-issued-JWT refresh endpoint.** When our JWT expires (24–72h) the user re-authenticates via Strava (a redirect, no re-consent if the Strava grant is still valid). Silent refresh is a later optimization.
- **No profile-update endpoint.** `UserService.updateProfile` exists in the domain model but is not exposed in the MVP surface; profile fields come from Strava at provisioning.
- **No Strava-token endpoints.** `TokenManager` is an in-process interface used only by Activity Ingestion — never HTTP.
- **No in-app "disconnect" endpoint** (`USER_REQUESTED` revocation). Defined in the events contract but reserved for post-MVP.
- **No admin/roles endpoints.** Single user type in MVP (see [database.md](database.md)).

---

## Next documents in this BC

- Sequence diagram: `diagrams/sequence/strava-oauth-login.md` — the full login flow with the event emit points
- [domain-model.md](domain-model.md), [database.md](database.md), [events.md](events.md) — already written
- [token-management.md](../../technical-notes/token-management.md) — already written
- [jwt-and-keys.md](../../technical-notes/jwt-and-keys.md) — *to be written* — `JwtEncoder`/`JwtDecoder` setup, RS256 key loading, JWKS, `jti` blacklist
