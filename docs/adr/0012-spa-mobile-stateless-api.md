# ADR 0012: SPA and mobile clients; the backend is a stateless API with ID-token login

- **Status:** Accepted
- **Date:** 2026-10-16
- **Related:** [ADR 0009](0009-identity-spring-security.md) (identity in-app, own issuer), [ADR 0011](0011-identity-provider-signup.md) (identity provider vs data sources), [ADR 0013](0013-access-and-refresh-tokens.md) (lifecycle of the tokens this login issues)

## Context

Spring Security's default for "log in with an OAuth/OIDC provider" is a backend-driven redirect flow: the backend sends the browser to the provider, receives the authorization code on its own callback and completes the login server-side (`oauth2Login`; `oauth2Client` for connecting data sources). That design fits server-rendered pages.

Aperitivo's clients are:

- a **single-page application** (React) as the web client;
- a **mobile app** later;
- possibly **other backends** consuming the API.

Against those clients, backend-driven redirects run into four problems:

1. **A redirect cannot hand a token to JavaScript.** The OAuth callback ends in a `302`, which no script reads. A browser client then needs a second step (a cookie and a follow-up call) just to obtain the access token it will send as `Authorization: Bearer`.
2. **A native app cannot receive the result.** The flow completes in the system browser, so a cookie set there never reaches the app. Making it work needs a custom-scheme deep link, a one-time code and an extra exchange endpoint. Google does not allow OAuth inside embedded web views. In practice the mobile app would need a second login path anyway.
3. **The redirect flow is stateful by default.** Spring keeps the authorization request (`state`, `nonce`, PKCE verifier), the saved request and the resulting `SecurityContext` in the `HttpSession`. Several instances then need Spring Session or sticky routing. Removing the session means replacing `AuthorizationRequestRepository`, `SecurityContextRepository`, `RequestCache` and the CSRF repository by hand.
4. **Two authentication mechanisms appear in one browser.** The session that carries the redirect easily becomes the authentication for pages and connect endpoints, next to the bearer JWT used by `/api/**`. Two filter chains with two notions of "who is the user" drift apart.

None of these problems exist when the client obtains the Google ID token itself and the backend only verifies it. Google documents this pattern as authenticating with a backend server ([verify the Google ID token on your server side](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token)). Native Google sign-in SDKs return the same ID token.

## Decision

**Clients talk to Google and Strava in the browser or device. The backend is a stateless JSON API: it verifies a Google ID token at login, exchanges data-source authorization codes server-side, and authenticates every other request by our own bearer token.**

### 1. Client topology

- The web client is a **React SPA**, served from the **same origin** as the API through a reverse proxy: `https://aperitivo.app/` → SPA, `https://aperitivo.app/api/**` → Spring. The browser sees one origin, so **CORS is not enabled at all**. In development a Vite proxy keeps the same arrangement.
- A **mobile app** (post-MVP) uses the same API and the same login endpoint.
- **No server-rendered pages.** Until the SPA exists, the API is exercised with HTTP clients (Postman, IntelliJ HTTP client) carrying a bearer token. Integration tests mint tokens directly.

### 2. Login is an ID-token exchange

```
client ──(Google Identity Services / native SDK)──► Google ──► ID token in the client
client ── POST /api/auth/google { idToken } ──► API
API: verify ID token → provision or resolve User (ADR 0011) → issue our tokens (ADR 0013)
```

- The client obtains a Google **ID token**. On the web this is the Google Identity Services button; on mobile it is the native SDK. The Google **access token** is never requested or accepted: it is meant for Google APIs and is not bound to our audience, so using it as proof of identity would enable token substitution.
- The backend verifies the ID token **with Spring Security components, not hand-written code**:
  - `NimbusJwtDecoder.withIssuerLocation("https://accounts.google.com")` checks the signature against Google's JWKS, cached and selected by `kid`;
  - `JwtValidators.createDefaultWithValidators(new OidcIdTokenValidator(googleRegistration))` checks `exp`/`iat` and, via the OIDC validator, `iss`, `aud` (our Google client ID) and `azp`;
  - a `JwtClaimValidator` checks `email_verified`;
  - `nonce` is compared when the client supplied one.

  The Google `ClientRegistration` already in `application.yaml` is reused as configuration (client ID, issuer). The verifier is encapsulated in an IAM component (`GoogleIdTokenVerifier`), **not** exposed as a `JwtDecoder` bean, so the resource server can never pick it up by accident.
- On success the existing provisioning service (`OAuth2GoogleLoginCallbackService.handleLogin`) resolves or creates the `User` by `(issuer, sub)`, exactly as [ADR 0011](0011-identity-provider-signup.md) specifies. The Google token is then discarded.
- The response carries our access token in the JSON body and our refresh token as an `HttpOnly` cookie (web) — see [ADR 0013](0013-access-and-refresh-tokens.md).
- **A Google ID token is never accepted as a bearer token on the API.** Google controls its lifetime (about an hour), its `sub` is the provider's identifier rather than our `user_id`, and we can neither revoke it nor add claims to it. It proves identity once, at the door.

### 3. One stateless security filter chain, deny by default

| Path | Rule |
|---|---|
| `POST /api/auth/google`, `POST /api/auth/refresh`, `POST /api/auth/logout` | `permitAll` (their credential is the ID token or the refresh cookie, checked by the controller) |
| `GET /.well-known/jwks.json`, `GET /actuator/health`, `/error` | `permitAll` |
| `/api/**` | `authenticated` (our bearer token) |
| anything else | `denyAll` |

- **No `securityMatcher`.** Requests that match no chain bypass Spring Security entirely, and Boot's default chain backs off as soon as any `SecurityFilterChain` bean exists, so a matcher-scoped chain would silently leave every other path (for example actuator endpoints) unprotected.
- `sessionManagement(STATELESS)` is set, and no component creates an `HttpSession`. A test asserts that no response sets `JSESSIONID`.
- **Resource server** with **our** decoder passed explicitly (`.jwt(j -> j.decoder(ourJwtDecoder))`) and a converter that turns the JWT into a `CurrentUser(UUID userId)` principal. Other modules depend on `CurrentUser` from IAM's published API, never on JWT or OAuth types.
- Unauthenticated requests get `401` with a Problem Details body from a custom `AuthenticationEntryPoint`.
- **CSRF protection is disabled.** API requests authenticate by a header the browser never attaches by itself. The only cookie-borne credential is the refresh cookie, scoped to `/api/auth`; it is protected by `SameSite=Strict`, by those endpoints accepting only `application/json` (a cross-origin JSON `POST` needs a CORS preflight, which no origin passes), and by CORS being off.
- Clients do not send `Authorization` to `/api/auth/**`: an expired bearer token on a `permitAll` endpoint still fails in `BearerTokenAuthenticationFilter` with `401`.

### 4. Data sources are connected by a client-driven authorization-code flow

```
SPA: state → sessionStorage; navigate to Strava /oauth/authorize?…&redirect_uri=https://aperitivo.app/connect/strava/callback&state=…
Strava ──302──► SPA route /connect/strava/callback?code&scope&state
SPA: check state, drop code from the URL, obtain a fresh access token (ADR 0013)
SPA ── POST /api/connect/strava { code, scope } + Bearer ──► API
API: exchange code with client_secret → check scopes → ConnectedSourceService.connect(userId from bearer, …)
```

- The **SPA owns the browser leg**: it generates and verifies `state`, which is what defeats a forged callback that would attach an attacker's Strava account to the victim's user.
- The **backend owns the confidential leg**. It exchanges the code with `RestClientAuthorizationCodeTokenResponseClient` (Spring Security 7) and the existing `strava` `ClientRegistration`, so the `client_secret` never leaves the server. The code is single-use and useless without the secret.
- The `user_id` comes **only from our bearer token**. The provider contributes the athlete and the tokens, nothing else. This invariant from [ADR 0011](0011-identity-provider-signup.md) is unchanged; only the mechanism that carries it changed.
- Strava lets the user deselect scopes, so the backend rejects a connection without `activity:read_all`.
- `GET /api/me/sources` and `DELETE /api/me/sources/{provider}` from ADR 0011 are unchanged.

### 5. Linking a second sign-in method

When Apple is added, an authenticated user links it with `POST /api/me/identities/{provider} { idToken }`, which uses the same verifier as login. The rules of [ADR 0011](0011-identity-provider-signup.md) §4 hold unchanged: login never merges, a conflict is `409`, and the last sign-in method cannot be removed.

### 6. Server push (SSE) uses a fetch-based client

The notifications stream (`GET /api/sse/events`, [ADR 0007](0007-sse-not-websocket.md)) authenticates like any other API request, with `Authorization: Bearer`. The browser's native `EventSource` cannot set request headers, so the SPA opens the stream with `fetch` and a streaming SSE parser (for example `@microsoft/fetch-event-source`). That library also implements reconnection with `Last-Event-ID`, and it lets the client refresh the access token before reconnecting. The server side is unchanged (`SseEmitter`). The stream is authenticated once, at connect time, so an access token expiring mid-stream does not drop an open connection. The mobile app does the same.

### Not used

`oauth2Login`, the `oauth2Client` redirect filter, authentication success/failure handlers, `HttpSession`, and server-rendered pages.

## Alternatives considered

- **Backend-driven login (`oauth2Login`) with a short-lived handshake session.** This is a sound, mainstream design, and Spring's default. The session only lives for the redirect, the API stays stateless, and Spring Session on Redis makes it multi-instance. Rejected because of the target clients: the redirect cannot deliver the access token to the SPA without an extra step, and the mobile app would need its own deep-link and one-time-code exchange, which is a second login path.
- **Hybrid: `oauth2Login` for the web, ID-token exchange for mobile.** Two chains split by URL make this technically easy. Rejected: two login paths double the attack surface and the tests while serving the same purpose.
- **Backend-for-Frontend (tokens never reach the browser; session cookie only).** This is the strongest browser posture, and the one the IETF [OAuth 2.0 for Browser-Based Applications](https://datatracker.ietf.org/doc/draft-ietf-oauth-browser-based-apps/) draft prefers. Rejected for now: browser authentication becomes a server session, diverging from the bearer contract the mobile app and other services use. Worth revisiting if the threat model ever makes XSS-driven token theft unacceptable.
- **Own authorization server (Spring Authorization Server or Keycloak) with Google upstream.** This is complete by the standards, including `/oauth2/token` and refresh grants out of the box. Rejected for the reason [ADR 0009](0009-identity-spring-security.md) rejected an identity server: a whole extra component for a narrow need. Revisit if third-party clients appear.
- **Accept the Google ID token as the API bearer token.** Rejected: see §2. The lifetime, subject, revocation and claims all belong to Google.
- **Backend-driven connect with `state → user_id` in Redis** (`POST /api/connect/strava/start` with a bearer, callback to the backend). This works, but needs a custom `AuthorizationRequestRepository` and server-side state for a problem the SPA already solves with `state` in `sessionStorage`.
- **Cookie-based or ticket-based SSE authentication** (so that the native `EventSource` could be used). Cookie transport contradicts the bearer-only API. A one-time ticket in the query string needs another endpoint and server-side ticket storage, and puts a credential in the URL. A fetch-based SSE client keeps one authentication mechanism (§6).
- **Keep Thymeleaf + HTMX until React.** Rejected: page navigations carry no bearer, so the demo would need cookie transport, a header-or-cookie `BearerTokenResolver`, form CSRF and server-side silent refresh, all of which are thrown away when the SPA arrives.

## Consequences

**Positive:**
- **One login endpoint for web and mobile.** Both present a Google ID token.
- **No server-side session anywhere.** Any number of instances, no Spring Session, no sticky routing.
- **A much smaller security configuration:** one chain, with no handshake state to persist and no login CSRF on redirects.
- **All cryptographic validation comes from Spring Security.** The only hand-written parts are the controllers.
- **The full OAuth 2.0 authorization-code flow with a confidential client is still exercised**, in Strava connect, where long-lived provider tokens make it necessary.

**Negative:**
- **The frontend is in scope:** a React SPA and, later, a mobile app ([overview.md](../architecture/overview.md)).
- **Login depends on Google's client SDK** (Google Identity Services on the web, the native SDK on mobile) rather than on server-side redirects.
- **The Google ID token and the Strava code pass through client JavaScript.** Mitigations: the ID token is audience-bound and short-lived, the code is single-use and useless without the secret, the code is removed from the URL immediately, and CSP and dependency hygiene limit XSS.
- **Hand-written controllers** (`/api/auth/*`, `/api/connect/{provider}`) instead of framework-provided callback handling.
- **Only OIDC providers can be login methods**, since an ID token is required. This is consistent with ADR 0011.

**Risks:**
- **Login CSRF on `/api/auth/google`:** a third-party page submits the attacker's ID token from the victim's browser. Mitigation: JSON-only requests, no CORS, and an optional `nonce`.
- **Replay of a stolen ID token within its lifetime.** Mitigation: `nonce`, and optionally rejecting tokens whose `iat` is older than a few minutes.
- **Google issuer forms.** Google ID tokens may carry `iss` as `accounts.google.com` or `https://accounts.google.com`. Mitigation: an integration test with real tokens; accept both forms if needed.
- **Drift: a Google decoder registered as a `JwtDecoder` bean** and picked up by the resource server. Mitigation: the verifier is not a bean of that type, and a test asserts that a valid Google ID token is rejected as a bearer token on `/api/**`.
- **Unsecured paths outside the chain.** Mitigation: no `securityMatcher`, `denyAll` as the fallback, and a test for an unknown path.

**Follow-ups:**
- Code (the current implementation still uses `oauth2Login`/`oauth2Client` and two chains): replace `OAuth2SecurityConfig` with the single chain; add `GoogleIdTokenVerifier`, the auth controller, the connect controller and the `CurrentUser` converter; remove `OAuth2GoogleLoginSuccessHandler`, `OAuth2GoogleLoginFailureHandler` and the placeholder `/` controller. Tracked in [plans/identity.md](../plans/identity.md).

## References

- [ADR 0009: Identity in-app with Spring Security and our own JWT issuer](0009-identity-spring-security.md)
- [ADR 0011: Identity-provider signup (Google); data sources connected on demand](0011-identity-provider-signup.md)
- [ADR 0013: Short-lived access tokens with rotating refresh tokens](0013-access-and-refresh-tokens.md)
- [Google — Verify the Google ID token on your server side](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token)
- [IETF — OAuth 2.0 for Browser-Based Applications (draft)](https://datatracker.ietf.org/doc/draft-ietf-oauth-browser-based-apps/)
- [RFC 6749 §10.12 — Cross-Site Request Forgery (`state`)](https://www.rfc-editor.org/rfc/rfc6749#section-10.12)
- [Spring Security 7 — OAuth 2.0 Login, advanced configuration (ID token decoder)](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/login/advanced.html)
- [Spring Security 7 — Resource Server JWT](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/resource-server/jwt.html)
