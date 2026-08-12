# Sequence: Strava connect

Connecting a Strava account as a **data source**, initiated by an **already-authenticated** user from inside the app. This is the flow that acquires tokens, creates the `ConnectedSource`, and triggers ingestion — everything the login flow deliberately does not do.

The critical property: **this flow cannot log anyone in.** It runs on `oauth2Client`, whose filter has no code path that mints an authentication, so the `SecurityContext` is untouched from start to finish. The `user_id` written to `connected_sources` comes from our own JWT, never from Strava.

Decision: [ADR 0011](../../../../adr/0011-identity-provider-signup.md). Token storage: [ADR 0003](../../../../adr/0003-token-storage-strategy.md). Endpoint detail: [api.md](../../api.md). Event contracts: [events.md](../../events.md).

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser (authenticated)
    participant SS as Spring Security
    participant ST as Strava
    participant CH as connect handler
    participant CSS as ConnectedSourceService
    participant EP as event_publication (Outbox)
    participant ING as Activity Ingestion

    Note over B,SS: 1 — Authorization request (framework, but PROTECTED)
    B->>SS: GET /connect/strava  (JWT cookie)
    SS->>SS: authorize: authenticated? else 401 / redirect to login
    SS->>ST: 302 to authorize (client_id, scopes, state)
    ST-->>B: consent screen
    B->>ST: approve
    ST->>SS: 302 → /connect/oauth2/code/strava?code&state&scope

    Note over SS,ST: 2 — Code exchange (OAuth2AuthorizationCodeGrantFilter)
    SS->>SS: validate state
    SS->>ST: POST /oauth/token (code, client_secret)
    ST-->>SS: access + refresh tokens (OAuth2AuthorizedClient)
    Note over SS: SecurityContext UNCHANGED.<br/>No OAuth2UserService is invoked.

    Note over CH,ST: 3 — Athlete lookup (explicit — nothing does it for us)
    SS->>CH: handle callback (userId from JWT)
    CH->>ST: GET /api/v3/athlete
    ST-->>CH: athlete id, name, avatar

    Note over CSS,EP: 4 — Connect — one @Transactional
    CH->>CSS: connect(userId, STRAVA, athleteId, tokens)
    CSS->>CSS: create or reactivate row;<br/>AES-GCM encrypt tokens; status=ACTIVE;<br/>reset failureCount / lastError
    CSS->>EP: publish IntegrationConnectedEvent (on transition to ACTIVE)
    CSS-->>CH: ConnectedSourceEntity
    Note over CSS,EP: connected_sources + event_publication<br/>commit atomically (Outbox — no dual write)

    CH-->>B: 302 to the sources page

    Note over EP,ING: 5 — After commit (async, at-least-once)
    EP-)ING: deliver IntegrationConnectedEvent
    Note over ING: kicks off initial backfill → see initial-backfill.md
```

## Notes on the flow

- **Phase 1 is protected, and that is the security control.** `/connect/**` requires an authenticated session; an anonymous request is rejected before any redirect to Strava happens. Hiding the "Connect Strava" button for logged-out visitors is presentation, not authorization — the rule on the filter chain is what makes the flow safe.
- **Phase 2 uses `oauth2Client`, not `oauth2Login`.** `OAuth2AuthorizationCodeGrantFilter` performs the code exchange and stores an `OAuth2AuthorizedClient`; it does **not** authenticate anyone. Completing a Strava OAuth flow therefore cannot change who you are logged in as — which is precisely why this is the correct mechanism for attaching a source, and why a Strava callback is structurally unusable as a login backdoor. Reference: [Spring Security — Authorization Grant Support](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/client/authorization-grants.html).
- **Phase 3 is explicit because nothing does it for us.** `oauth2Client` never invokes an `OAuth2UserService`, so unlike a login flow there is no automatic profile fetch. The handler calls `GET /api/v3/athlete` itself to obtain the athlete id, which becomes `provider_user_id` on the `ConnectedSource`.
- **Both OAuth flows default to a redirect URI under `/login/oauth2/code/*`.** They are separated by giving the Strava `ClientRegistration` an explicit `redirect-uri` of `{baseUrl}/connect/oauth2/code/{registrationId}`. `OAuth2AuthorizationCodeGrantFilter` matches the callback against the registration's own redirect URI, so the path is free-form; keeping it outside `/login/oauth2/code/*` is what guarantees the login filter never sees a Strava callback. The initiation path `/connect/strava` comes from a `DefaultOAuth2AuthorizationRequestResolver` with base URI `/connect`. Since the redirect URI belongs to the registration, a provider that were ever *both* a login method and a linkable source would need two registrations. In MVP the question does not arise. Endpoint detail: [api.md](../../api.md).
- **Phase 4 is one transaction,** and it is the only place `connected_sources` is written during a user-initiated flow. Tokens are AES-GCM encrypted by the entity converter; the DB never sees plaintext. The Strava tokens live **only** in our `ConnectedSource` — Spring's `OAuth2AuthorizedClientService` is not our durable token store ([ADR 0003](../../../../adr/0003-token-storage-strategy.md)); the `OAuth2AuthorizedClient` is a transient carrier we read once.
- **The event fires on the transition, not on the request.** Re-running the connect flow against an already-`ACTIVE` source refreshes its tokens and publishes **nothing** — no state changed. See the three cases tabulated in [domain-model.md](../../domain-model.md).
- **Phase 5 is after commit.** Only once the transaction has committed does Modulith deliver `IntegrationConnectedEvent` to Activity Ingestion (`@ApplicationModuleListener`, async, at-least-once). This is the **sole** trigger for the initial backfill — see [initial-backfill.md](../../../activity-ingestion/diagrams/sequence/initial-backfill.md).

## Variations (not redrawn)

- **Reconnect after a revoke, same athlete.** Phases 1–3 are identical. In phase 4 the existing row is **reactivated in place** — tokens overwritten, `failureCount`/`lastError` reset, status back to `ACTIVE` — because `UNIQUE (provider, provider_user_id)` makes a second row impossible. The transition **does** emit `IntegrationConnectedEvent`, so phase 5 runs and Ingestion resumes (incremental catch-up via the existing `SyncCursor`; full backfill only if none exists — Ingestion's decision, not encoded in the event). Note the consequence for consumers: `connectedSourceId` is **unchanged**, so it is not a valid per-occurrence dedup key ([events.md](../../events.md)).

  Writing fresh tokens onto a `REVOKED` row without restoring `ACTIVE` is the specific bug this step exists to prevent: the user would see a successful reconnect while `TokenManager` kept rejecting every request as inactive, and ingestion would stay silently dead.

- **Re-connect of an already-active source.** Tokens and scopes are refreshed; no transition, **no event**, no backfill.

- **Connecting a different Strava athlete.** A new row is inserted; the old revoked row is kept for history (permitted by the partial unique index). See the reconnect table in [database.md](../../database.md).

## Failure paths (not redrawn)

- **Anonymous request to `/connect/strava`** → `401` / redirect to login. No Strava redirect is issued at all.
- **User denies consent**, or the **code exchange fails**, or **`GET /api/v3/athlete` fails** → redirect back to the sources page with an error indication. No `ConnectedSource` is created or modified, no event is emitted, and **the user stays logged in** — a failed connect has no bearing on the session. That independence is exactly what `oauth2Client` buys.
- **The athlete is already connected to another Aperitivo user** → `409 …/errors/source-already-linked`. The source is never silently moved between accounts, and the response never identifies the other account ([api.md](../../api.md)).
- Because phase 4 is one transaction, a failure inside it rolls back the state change **and** the `event_publication` row, so no event is ever delivered for a connect that did not fully succeed.

## Related

- [google-login.md](google-login.md) — the separate flow that creates the account this connect attaches to
- [revocation-flow.md](revocation-flow.md) — the reverse transition, and what triggers it
- [token-refresh.md](token-refresh.md) — what happens to the stored tokens afterwards
- [domain-model.md](../../domain-model.md) — `ConnectedSourceService.connect`, the three external channels
- [api.md](../../api.md) — `oauth2Login` vs `oauth2Client`, endpoint surface, error shapes
- [events.md](../../events.md) — `IntegrationConnectedEvent` contract and dedup guidance
- [initial-backfill.md](../../../activity-ingestion/diagrams/sequence/initial-backfill.md) — the backfill that phase 5 triggers
