# Sequence: Strava OAuth login

The full "Sign in with Strava" flow, from the initial click to JWT issuance, for a **first-time** sign-up. It shows which parts are Spring Security framework behaviour and which are our seams (`OAuth2UserService`, the success handler, `StravaOAuthService.handleCallback`), the single transaction in which the user, the connected source, and the event-publication rows are committed (the Outbox), and the after-commit point where `IntegrationConnectedEvent` reaches Activity Ingestion. Provisioning detail: [domain-model.md](../../domain-model.md). Endpoint and auth detail: [api.md](../../api.md). Event contracts: [events.md](../../events.md).

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant SS as Spring Security
    participant ST as Strava
    participant SOS as StravaOAuthService
    participant US as UserService
    participant CSS as ConnectedSourceService
    participant EP as event_publication (Outbox)
    participant ING as Activity Ingestion

    Note over B,ST: 1 — Authorization request (framework)
    B->>SS: GET /oauth2/authorization/strava
    SS->>ST: 302 to authorize (client_id, scopes, state)
    ST-->>B: consent screen
    B->>ST: approve
    ST->>SS: 302 → /login/oauth2/code/strava?code&state

    Note over SS,ST: 2 — Code exchange (framework)
    SS->>SS: validate state
    SS->>ST: POST /oauth/token (code, client_secret)
    ST-->>SS: access + refresh tokens (OAuth2AuthorizedClient)

    Note over SS,ST: 3 — Profile load (custom OAuth2UserService)
    SS->>ST: GET /api/v3/athlete
    ST-->>SS: athlete → StravaProfile

    Note over SOS,EP: 4 — Provisioning — one @Transactional (success handler → handleCallback)
    SS->>SOS: handleCallback(StravaOAuthResult)
    SOS->>US: create UserEntity (STRAVA, athleteId)
    US-->>SOS: UserEntity
    SOS->>EP: publish UserRegisteredEvent
    SOS->>CSS: create ConnectedSource (AES-GCM tokens, status=ACTIVE)
    CSS-->>SOS: ConnectedSourceEntity
    SOS->>EP: publish IntegrationConnectedEvent
    SOS-->>SS: UserEntity
    Note over SOS,EP: users + connected_sources + event_publication<br/>commit atomically (Outbox — no dual write)

    Note over SS,B: 5 — JWT issuance (success handler)
    SS->>SS: mint RS256 JWT (sub=user_id, iss, iat, exp, jti)
    SS-->>B: 302 → / · Set-Cookie: token=JWT; HttpOnly; Secure; SameSite=Lax

    Note over EP,ING: 6 — After commit (async, at-least-once)
    EP-)ING: deliver IntegrationConnectedEvent
    Note over ING: kicks off initial backfill → see initial-backfill.md
```

## Notes on the flow

- **Phases 1–2 are pure framework.** We write no controller for the authorize redirect or the code exchange; we supply the `ClientRegistration` (client id/secret, URIs, scopes `read,activity:read_all,profile:read_all`) in configuration. We are a **confidential** client, so the code-for-token exchange is server-to-server. The `state` parameter is the framework's CSRF guard for the OAuth flow.
- **Phase 3 is our first seam.** Strava is OAuth 2.0, not OIDC — there is no ID token — so a custom `OAuth2UserService` loads the athlete from `GET /api/v3/athlete` to build the `StravaProfile`.
- **Phase 4 is the heart of provisioning, and it is one transaction.** The success handler assembles the `StravaOAuthResult` (profile + tokens pulled out of the `OAuth2AuthorizedClient`) and calls `StravaOAuthService.handleCallback`. Inside a single `@Transactional` method: the `UserEntity` is created, the `ConnectedSourceEntity` is created with **AES-GCM-encrypted** tokens, and both events are published. Spring Modulith writes the event rows into `event_publication` **in that same transaction** — so the business state and the events commit together or not at all (Outbox; no dual write). The Strava tokens are stored only in our `ConnectedSource`, never in Spring's `OAuth2AuthorizedClientService`.
- **Phase 5 delivers the identity.** The success handler mints our RS256 JWT (`sub` = internal `user_id`, never `providerUserId`) and sets it as the `HttpOnly; Secure; SameSite=Lax` cookie on the redirect to the app home. API clients would instead read the token and send it as `Authorization: Bearer` — same token, two transports (see [api.md](../../api.md)).
- **Phase 6 is after commit.** Only once the transaction has committed does Modulith deliver `IntegrationConnectedEvent` to Activity Ingestion (`@ApplicationModuleListener`, async, at-least-once). Ingestion then starts the initial backfill — a separate flow, see `initial-backfill.md`. `UserRegisteredEvent` has no consumer in the MVP, so no delivery arrow is drawn for it.

## Login variations (not redrawn)

The diagram is the **first-time sign-up** (UC-1), where both events fire. The two other login cases differ only in phase 4:

- **Returning user, source already `ACTIVE` (UC-2).** `handleCallback` resolves the existing `UserEntity` and the existing `ConnectedSource`; it creates nothing new. **No events** are emitted — neither `UserRegisteredEvent` (the user already exists) nor `IntegrationConnectedEvent` (no `ACTIVE` transition). Phases 1–3 and 5 are identical; the user simply receives a fresh JWT.
- **Reconnect after a prior revoke.** The existing `UserEntity` is resolved (no `UserRegisteredEvent`), but a **fresh** `ConnectedSource` row becomes `ACTIVE`, replacing the `REVOKED` one (per the partial unique index in [database.md](../../database.md)). That `ACTIVE` transition **does** emit `IntegrationConnectedEvent` — so phase 6 runs, and Ingestion resumes (incremental catch-up via the existing `SyncCursor`, full backfill only if none exists — Ingestion's decision, not encoded in the event).

## Failure paths (not redrawn)

- **User denies consent on Strava**, or the **code exchange fails** → Spring Security's failure handler redirects to the login page with an error indication. No `User`, no `ConnectedSource`, no event.
- **Profile load (`/api/v3/athlete`) fails** → the login fails the same way; nothing is provisioned. Because provisioning is one transaction, a failure anywhere in phase 4 rolls back the whole thing — including the `event_publication` rows — so no event is ever delivered for a login that did not fully succeed.

## Related

- [domain-model.md](../../domain-model.md) — `handleCallback`, the two Strava channels, the `sub = user_id` rule
- [api.md](../../api.md) — endpoint surface, cookie/Bearer auth model
- [events.md](../../events.md) — `UserRegisteredEvent` / `IntegrationConnectedEvent` contracts
- [token-management.md](../../../../technical-notes/token-management.md) — what happens to the stored tokens afterwards
- [initial-backfill.md](../../../activity-ingestion/diagrams/sequence/initial-backfill.md) — *to be written* — the backfill that phase 6 triggers
