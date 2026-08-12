# Identity & Access — API

The HTTP surface of the IAM Bounded Context: the Google login flow, the Strava connect flow, the current-user endpoint, source/identity management, and logout. Much of the "login" and "connect" surface is **provided by Spring Security**, not hand-written controllers — this document is explicit about which parts are framework configuration and which are our code.

Who may create an account, and why sign-in and data sources are separate: [ADR 0011](../../adr/0011-identity-provider-signup.md). Authentication model and JWT structure: [ADR 0009](../../adr/0009-identity-spring-security.md). Entities and service seams: [domain-model.md](domain-model.md). Events fired: [events.md](events.md).

## Endpoint surface

| Method & path | Owner | Auth | Purpose |
|---|---|---|---|
| `GET /oauth2/authorization/google` | Spring Security (framework) | none | Initiate Google login — redirect to Google's authorize URL |
| `GET /login/oauth2/code/google` | Spring Security (framework) + our seams | none (the OAuth `state` guards it) | Google redirect/callback — provisioning + JWT issuance |
| `GET /connect/strava` | Spring Security (framework) | **required** | Initiate the Strava connect flow |
| `GET /connect/oauth2/code/strava` | Spring Security (framework) + our seams | **required** | Strava redirect/callback — store tokens, activate the source |
| `GET /api/me` | our controller | required | Current authenticated user |
| `GET /api/me/sources` | our controller | required | The user's connected data sources |
| `DELETE /api/me/sources/{provider}` | our controller | required | Disconnect a data source |
| `GET /api/me/identities` | our controller | required | The user's sign-in methods |
| `DELETE /api/me/identities/{provider}` | our controller | required | Remove a sign-in method |
| `POST /api/auth/logout` | our controller | required | Invalidate the session (clear cookie, blacklist `jti`) — optional in MVP |

Notes on what is deliberately absent:

- **No signup/registration endpoint.** Provisioning happens implicitly inside the Google callback on first login.
- **No Strava login endpoint.** Strava is not a sign-in method; it can only be reached from an authenticated session. See [ADR 0011](../../adr/0011-identity-provider-signup.md).
- **No REST endpoint for Strava tokens.** Those are accessed only in-process via `TokenManager`.
- **The Strava deauthorization webhook is not here.** It is received by Activity Ingestion's `WebhookController` and routed back to `ConnectedSourceService.markRevoked` — see the "three channels" section of [domain-model.md](domain-model.md).

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
// Illustrative resource-server + login + connect wiring.
http
    .oauth2Login(oauth -> oauth                          // SIGN-IN: replaces the SecurityContext
        .userInfoEndpoint(ui -> ui.oidcUserService(googleOidcUserService))
        .successHandler(jwtIssuingSuccessHandler))       // mint JWT, set cookie, redirect
    .oauth2Client(Customizer.withDefaults())             // CONNECT: authorization-code grant only
    .oauth2ResourceServer(rs -> rs
        .bearerTokenResolver(cookieOrHeaderBearerTokenResolver)
        .jwt(Customizer.withDefaults()))                 // validate our RS256 JWT
    .csrf(Customizer.withDefaults());
```

---

## Two OAuth flows, two pieces of Spring Security

This is the single most important implementation detail in this document. Login and connect are both browser OAuth redirects and look almost identical from the outside. They use **different filters**, start from **different authentication states**, and write to **different tables**.

| | Login (Google) | Connect (Strava) |
|---|---|---|
| DSL | `oauth2Login` | `oauth2Client` |
| Filter | `OAuth2LoginAuthenticationFilter` | `OAuth2AuthorizationCodeGrantFilter` |
| Caller | anonymous | **already authenticated** |
| SecurityContext | **replaced** with the new principal | **untouched** |
| Invokes `OAuth2UserService`? | yes | **no** |
| Writes | `users`, `user_identities` | `connected_sources` |
| Where `user_id` comes from | created or resolved from the provider `sub` | our own JWT — **never** from Strava |

Two consequences follow, and both are easy to get wrong:

1. **`oauth2Client` never authenticates anyone.** It performs the authorization-code exchange and stores the result in an `OAuth2AuthorizedClient`, and that is all. This is precisely why it is the right mechanism for connecting a source: completing a Strava OAuth flow must not be able to change who you are logged in as. It is also why a Strava connect callback is *impossible* to use as a login backdoor — the filter has no code path that mints an authentication.

   > Reference: [Spring Security — Authorization Grant Support](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/client/authorization-grants.html)

2. **`oauth2Client` does not call an `OAuth2UserService`.** Nothing fetches the athlete profile for us the way `oauth2Login` did. The connect handler must call `GET /api/v3/athlete` **explicitly** (via `StravaApiClient`) to learn the athlete id, which is the `provider_user_id` on the `ConnectedSource`.

Both flows default to a redirect URI under `/login/oauth2/code/*`, which is exactly the path `OAuth2LoginAuthenticationFilter` claims. They are separated by giving the Strava `ClientRegistration` an explicit distinct `redirect-uri`:

```yaml
spring.security.oauth2.client.registration.strava.redirect-uri: "{baseUrl}/connect/oauth2/code/{registrationId}"
```

`OAuth2AuthorizationCodeGrantFilter` matches the callback against the registration's own `redirect-uri`, so the path is free-form (Spring's own reference example uses `{baseUrl}/authorized/okta`). Keeping it outside `/login/oauth2/code/*` is what guarantees the login filter never sees a Strava callback.

The friendlier initiation path `/connect/strava` — rather than the framework default `/oauth2/authorization/strava` — comes from giving the grant its own resolver base URI:

```java
new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/connect")
```

Because the redirect URI is a property of the registration, **a provider that were ever both a login method and a linkable source would need two registrations** (e.g. `google` and `google-connect`). In MVP the question does not arise: Google is login-only, Strava is connect-only.

**The connect endpoints are protected.** `/connect/**` requires an authenticated session — an anonymous request is a `401`/redirect-to-login, not a silent account creation. Hiding the "connect" buttons in the UI is presentation, not a security control; the authorization rule is the control.

---

## Flow 1 — login with Google

```mermaid
sequenceDiagram
    participant B as Browser
    participant SS as Spring Security
    participant G as Google
    participant IAM as IAM services

    B->>SS: GET /oauth2/authorization/google
    SS-->>B: 302 redirect to Google authorize (client_id, scopes, state, nonce)
    B->>G: GET /o/oauth2/v2/auth
    G-->>B: consent page
    B->>G: user approves
    G-->>B: 302 redirect to /login/oauth2/code/google?code=...&state=...
    B->>SS: GET /login/oauth2/code/google?code=...&state=...
    SS->>G: POST /token (exchange code)
    G-->>SS: ID token (JWT) + access token
    SS->>SS: validate ID token signature, iss, aud, nonce, exp
    SS->>IAM: OidcUserService.loadUser(...)
    IAM-->>SS: ProviderIdentityProfile (sub, email, name, picture)
    SS->>IAM: success handler -> LoginService.login(profile)
    Note over IAM: resolve UserIdentity by (GOOGLE, sub);<br/>create User + UserIdentity if absent;<br/>publish UserRegisteredEvent on first login only
    IAM-->>SS: LoginResult(userId, isNewUser)
    SS-->>B: Set-Cookie token=JWT + 302 redirect (/connect if new, / otherwise)
```

### `GET /oauth2/authorization/google` — initiate

Pure framework. Spring Security redirects to Google with our `client_id`, the scopes (`openid`, `email`, `profile`), a generated `state` (the OAuth-flow CSRF guard) and a `nonce`. We write no controller; we supply the `ClientRegistration` in configuration. We are a **confidential** client — the `client_secret` stays server-side and the code-for-token exchange is server-to-server.

### `GET /login/oauth2/code/google` — callback

Google is **OIDC**, so the token response includes an **ID token**: a signed JWT whose `sub` claim is the stable subject identifier. Spring Security validates its signature (against Google's JWKS), issuer, audience, `nonce`, and expiry before any of our code runs. That validation is the whole reason an identity provider can be trusted to establish identity while Strava — plain OAuth 2.0, no ID token, no email — cannot.

Two seams of our code then run:

1. **`OidcUserService` (custom).** Maps the validated claims to a `ProviderIdentityProfile` (`provider=GOOGLE`, `providerUserId=sub`, `email`, `displayName`, `avatarUrl`). Note the type: OIDC providers go through `oidcUserService(...)`, not `userService(...)`.

2. **`AuthenticationSuccessHandler` (custom).** Calls `LoginService.login(profile)`, which resolves the `UserIdentityEntity` by `(GOOGLE, sub)` and either touches `lastLoginAt` or creates a `UserEntity` + `UserIdentityEntity` and publishes `UserRegisteredEvent`. It returns `LoginResult(userId, isNewUser)`. The handler then mints our JWT for that `userId`, sets the HttpOnly cookie, and redirects. Minting happens **outside** `login(...)` — the JWT is a transport concern of the login endpoint, not part of the provisioning transaction (see [domain-model.md](domain-model.md#why-it-returns-a-value-and-not-void)).

**No tokens are stored.** Google's access token and ID token are discarded once the identity is established; there is no background work to do on the user's behalf at Google. `user_identities` has no token columns ([database.md](database.md)).

**`isNewUser` decides the landing page.** A brand-new account has *no data whatsoever* — no sources, so no workouts, no analytics, nothing. It is redirected into the connect step. A returning user goes to the app home. This is the visible consequence of separating signup from connecting.

**Failure handling.** If the user denies consent, or the exchange or ID-token validation fails, Spring Security's failure handler redirects to the login page with an error indication. No `User` and no `UserIdentity` are created; no event is emitted.

---

## Flow 2 — connect Strava

```mermaid
sequenceDiagram
    participant B as Browser (authenticated)
    participant SS as Spring Security
    participant Strava
    participant IAM as IAM services

    B->>SS: GET /connect/strava   (JWT cookie present)
    SS->>SS: authorize: authenticated? else 401 / redirect to login
    SS-->>B: 302 redirect to Strava authorize (client_id, scopes, state)
    B->>Strava: GET /oauth/authorize
    Strava-->>B: consent page
    B->>Strava: user approves
    Strava-->>B: 302 redirect to /connect/oauth2/code/strava?code=...&state=...&scope=...
    B->>SS: GET /connect/oauth2/code/strava?code=...&state=...
    SS->>Strava: POST /oauth/token (exchange code)
    Strava-->>SS: access + refresh tokens
    Note over SS: OAuth2AuthorizedClient stored.<br/>SecurityContext UNCHANGED — still the same user.
    SS->>IAM: connect handler (userId from the JWT, not from Strava)
    IAM->>Strava: GET /api/v3/athlete
    Strava-->>IAM: athlete profile (id, name, avatar)
    IAM->>IAM: ConnectedSourceService.connect(userId, STRAVA, athleteId, tokens)
    Note over IAM: create or reactivate the source with AES-GCM-encrypted tokens;<br/>publish IntegrationConnectedEvent on the transition to ACTIVE
    IAM-->>B: 302 redirect to the sources page
```

The `userId` written to `connected_sources` comes from the **JWT of the current session**. Strava's response contributes the athlete id and the tokens, and nothing else. Identity is never inferred from a data-source provider — the invariant that survived from [ADR 0010](../../adr/0010-single-canonical-login-provider-superseded.md) into [ADR 0011](../../adr/0011-identity-provider-signup.md).

`ConnectedSourceService.connect` covers first connect, no-op re-connect of an already-active source, and reactivation after a revoke; which of those fires `IntegrationConnectedEvent` is specified in [domain-model.md](domain-model.md) and [events.md](events.md). Reactivation is **in-place** for the same athlete — see the reconnect table in [database.md](database.md).

> We do **not** use Spring Security's `OAuth2AuthorizedClientService` as the durable token store. Strava tokens live in our `ConnectedSource` (owned by IAM, accessed only via `TokenManager`) — see [ADR 0003](../../adr/0003-token-storage-strategy.md). The `OAuth2AuthorizedClient` is just the framework's transient carrier from which we extract the tokens once.

**Failure handling.** Denied consent or a failed exchange redirects back to the sources page with an error indication. No `ConnectedSource` is created or modified; no event is emitted. **The user stays logged in** — a failed connect has no bearing on the session, which is exactly the property `oauth2Client` buys us.

**Conflict.** If that Strava athlete is already connected to a different Aperitivo user, the connect is rejected with `409 …/errors/source-already-linked`. The source is never moved between accounts silently.

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
  "identities": [
    { "provider": "GOOGLE", "linkedAt": "2026-06-01T10:00:00Z" }
  ],
  "sources": [
    { "provider": "STRAVA", "status": "ACTIVE", "connectedAt": "2026-06-01T10:04:12Z" }
  ],
  "createdAt": "2026-06-01T10:00:00Z"
}
```

```java
public record UserDto(
        UUID              id,
        String            displayName,
        String            email,        // nullable — a provider may not release it
        String            avatarUrl,    // nullable
        List<IdentityDto> identities,   // how this user signs in — never empty
        List<SourceDto>   sources,      // connected data sources — MAY be empty
        Instant           createdAt
) {}

public record IdentityDto(
        IdentityProvider provider,      // GOOGLE in MVP
        Instant          linkedAt
) {}

public record SourceDto(
        Provider     provider,          // STRAVA in MVP
        SourceStatus status,            // ACTIVE | REVOKED | ERROR
        Instant      connectedAt
) {}
```

**`sources` is a list, and it may be empty.** That is not an edge case to code around — it is the normal state of every account between signing up and connecting something, and the permanent state of anyone who never connects. Clients must render an empty state, not an error. The previous single-valued `connection` field could not express this, because under the old model a user could not exist without a Strava source.

**`identities` is never empty** — invariant 4 in [domain-model.md](domain-model.md). Both lists deliberately omit provider user ids: the Google `sub` and the Strava athlete id are internal join keys, not client-facing data.

`sources` carries only IAM-owned health (`provider`, `status`) — enough for the UI to show "connected" vs "reconnect needed". It deliberately does **not** carry ingestion/backfill progress ("imported 124 of 540") — that is Activity Ingestion's concern, surfaced via SSE, not here. The `user_id` is resolved from the JWT `sub`; the endpoint never trusts a client-supplied id.

---

## `GET /api/me/sources`

The user's connected data sources — the same `SourceDto` list as above, for clients that only need the sources panel. Returns `200` with an array, possibly empty.

## `DELETE /api/me/sources/{provider}`

Disconnects a data source: sets it to `REVOKED` with reason `USER_REQUESTED` and publishes `IntegrationRevokedEvent`, so Ingestion stops scheduling syncs. Returns `204`.

Disconnecting a source **never** deletes the account and never touches `user_identities` — the user remains able to sign in and keeps their already-imported history. This is the disconnect half of the symmetry the connect flow creates: what an authenticated user attached, an authenticated user can detach. Under the previous model this endpoint could not exist as a plain `DELETE`, because removing the Strava source removed the only way to log in.

`404 …/errors/source-not-found` if the user has no source for that provider.

## `GET /api/me/identities`

The user's sign-in methods — the `IdentityDto` list. Returns `200`.

## `DELETE /api/me/identities/{provider}`

Removes a sign-in method. Returns `204`.

Rejected with `409 …/errors/last-identity` when it is the only remaining method: the account would still hold every workout the user ever imported while nobody could log into it. There is no support path to recover from that, so the API refuses rather than offering a confirmation dialog.

In MVP, with Google as the only identity provider, every user has exactly one identity — so this endpoint always returns `409`. It is specified now because the guard, not the endpoint, is the part that must exist before a second provider is added.

---

## `POST /api/auth/logout` (optional in MVP)

Invalidates the current session: clears the auth cookie (`Set-Cookie: token=; Max-Age=0`) and adds the token's `jti` to a Redis blacklist with TTL equal to the token's remaining lifetime, so a still-valid Bearer copy cannot be reused. Returns `204`.

This is **optional** for the MVP: with short-lived tokens, logout can be reduced to clearing the cookie (the Bearer path simply lets the token expire), and the `jti` blacklist can be deferred. See [ADR 0009](../../adr/0009-identity-spring-security.md) and the JWT technical note.

---

## Errors

| Status | When |
|---|---|
| `401 Unauthorized` | No token, expired token, bad signature, or a blacklisted `jti` on a protected endpoint — including an anonymous request to `/connect/**` |
| `403 Forbidden` | Valid token but a missing/invalid CSRF token on a cookie-authenticated mutating request |
| `404 Not Found` | `…/errors/source-not-found` — disconnecting a provider the user has not connected |
| `409 Conflict` | `…/errors/source-already-linked`, `…/errors/identity-already-linked`, `…/errors/last-identity` |
| `302 Found` | OAuth redirects (initiate → provider; callback → app home / sources page on success, error page on failure) |

Error bodies for the JSON API follow the project-wide [API error conventions](../../conventions/api-errors.md) (RFC 7807 `application/problem+json`); the OAuth endpoints redirect rather than return JSON, since they are browser-driven.

Conflict bodies name the constraint but never the other account:

```json
{
  "type": "https://aperitivo.dev/errors/source-already-linked",
  "title": "Data source already linked",
  "status": 409,
  "detail": "This Strava account is already connected to another Aperitivo user.",
  "instance": "/connect/oauth2/code/strava"
}
```

Naming the other user — or even confirming their email — would turn a conflict response into an account-enumeration oracle. The `detail` is deliberately as unhelpful as it can be while still telling the user what happened.

---

## What is intentionally NOT in this API (MVP)

- **No self-issued-JWT refresh endpoint.** When our JWT expires (24–72h) the user re-authenticates via Google (a redirect, usually with no re-consent). Silent refresh is a later optimization.
- **No profile-update endpoint.** `UserService.updateProfile` exists in the domain model but is not exposed in the MVP surface; profile fields come from the identity provider at provisioning.
- **No Strava-token endpoints.** `TokenManager` is an in-process interface used only by Activity Ingestion — never HTTP.
- **No account-merge endpoint.** Two accounts are never merged; see the alternatives section of [ADR 0011](../../adr/0011-identity-provider-signup.md).
- **No account-deletion endpoint** in MVP. Erasure spans all six BCs, so it is not an IAM-only concern.
- **No admin/roles endpoints.** Single user type in MVP (see [database.md](database.md)).

---

## Next documents in this BC

- Sequence diagrams: [google-login.md](diagrams/sequence/google-login.md), [strava-connect.md](diagrams/sequence/strava-connect.md), [revocation-flow.md](diagrams/sequence/revocation-flow.md)
- [domain-model.md](domain-model.md), [database.md](database.md), [events.md](events.md) — already written
- [token-management.md](../../technical-notes/token-management.md) — already written
- [jwt-and-keys.md](../../technical-notes/jwt-and-keys.md) — `JwtEncoder`/`JwtDecoder` setup, RS256 key loading, JWKS, `jti` blacklist

