# Use Cases

User-facing scenarios that Aperitivo supports. Used to validate that the architecture serves real product needs, and as input to API and event design.

Format: each use case has a **trigger**, **actors**, **flow**, and **success criteria**. Linked to the BCs and events involved.

---

## UC-1: First-time sign-up

**Trigger:** New user visits Aperitivo and clicks "Sign in with Google".

**Actors:** User, Aperitivo backend, Google (identity provider).

**Flow:**
1. User clicks "Sign in with Google".
2. Aperitivo (Spring Security `oauth2Login`) redirects to Google's OIDC authorize URL with our `client_id`, scopes `openid email profile`, a `state` and a `nonce`.
3. User authenticates and consents on Google.
4. Google redirects back with an authorization code.
5. Aperitivo exchanges the code for an ID token and validates it (signature against Google's JWKS, `iss`, `aud`, `nonce`, `exp`).
6. Aperitivo looks up a `UserIdentity` by `(GOOGLE, sub)`. None exists, so it creates an internal `User` (UUID) and a `UserIdentity`.
7. Aperitivo issues its own JWT for the internal user (`JwtEncoder`, RS256) and sets it as an HttpOnly cookie.
8. User lands on the **connect-a-source** step, because the account is empty.

**Events emitted:** `UserRegistered`.

**BCs involved:** Identity & Access only.

**Success criteria:** User holds a valid JWT and sees a prompt to connect a data source.

**Explicitly not part of this use case:** no Strava call, no tokens, no `ConnectedSource`, and **no backfill**. A freshly signed-up account holds no training data at all. Ingestion begins in UC-1b. See [ADR 0011](../adr/0011-identity-provider-signup.md).

---

## UC-1b: User connects Strava

**Trigger:** An **authenticated** user clicks "Connect Strava" — immediately after signing up, or any time later.

**Actors:** User (logged in), Aperitivo backend, Strava OAuth.

**Flow:**
1. User clicks "Connect Strava". The request carries their Aperitivo JWT; an anonymous request is rejected here.
2. Aperitivo (Spring Security `oauth2Client`) redirects to Strava's OAuth authorize URL with our `client_id` and scopes `read,activity:read_all,profile:read_all`.
3. User authorizes Aperitivo on Strava.
4. Strava redirects back to `/connect/oauth2/code/strava` with an authorization code.
5. Aperitivo exchanges the code for access + refresh tokens. **The security context is untouched** — this flow cannot log anyone in.
6. Aperitivo calls `GET /api/v3/athlete` explicitly to obtain the athlete id (`oauth2Client` invokes no `OAuth2UserService`).
7. Aperitivo creates — or reactivates, if a revoked row exists for that athlete — a `ConnectedSource` with AES-GCM-encrypted tokens, status `ACTIVE`. The `user_id` comes from the JWT, never from Strava.
8. **Asynchronously:** Activity Ingestion starts the initial backfill of the user's activity history.

**Events emitted:** `IntegrationConnected`.

**BCs involved:** Identity & Access (primary), Activity Ingestion (background backfill kick-off).

**Success criteria:** User has a `ConnectedSource` with `ACTIVE` status and sees a "we're syncing your history" UI state.

**Notes:** If the athlete is already connected to a different Aperitivo account, the connect is rejected with `409` and nothing is moved. A user may go through this flow more than once over the account's life (reconnect after revoke); each transition to `ACTIVE` re-emits `IntegrationConnected`.

---

## UC-2: Returning user logs in

**Trigger:** Existing user clicks "Sign in with Google".

**Flow:** Same as UC-1 through step 5. At step 6 the `UserIdentity` is found, so nothing is created and **no event is emitted**; `lastLoginAt` is touched. The user receives a fresh JWT and lands on the app home rather than the connect step.

**BCs involved:** Identity & Access.

**Notes:** Logging in is independent of source health. A user whose Strava connection is revoked still logs in normally — they are simply shown a "reconnect Strava" prompt. Under the previous Strava-login model these two failures were the same event; they are now unrelated.


---

## UC-3: New activity in Strava → ingested into Aperitivo

**Trigger:** User completes a workout in Strava; Strava sends a webhook to Aperitivo.

**Actors:** Strava (webhook sender), Aperitivo backend.

**Flow:**
1. Strava POSTs to Aperitivo's webhook endpoint with `{aspect_type: "create", object_type: "activity", object_id, owner_id}`.
2. Aperitivo verifies the webhook.
3. Aperitivo creates a `SyncJob` with idempotency key `(strava, object_id, create)`.
4. The SyncJob worker resolves the `ConnectedSource` for `owner_id` and retrieves a valid Strava token from `TokenManager`.
5. The worker calls `GET /activities/{object_id}` on the Strava API.
6. The raw payload is stored as `RawActivityPayload`.
7. Ingestion BC emits `ActivityIngested`.
8. Workout Catalog consumes it, normalizes the payload, persists a `Workout`, emits `WorkoutCreated`.
9. Performance Analytics consumes `WorkoutCreated`, materializes streams, recomputes derived metrics, possibly emits `PersonalRecordSet`.
10. Training Planning consumes `WorkoutCreated`, attempts to match against a pending `ScheduledSession`.
11. Notifications consumes any `PersonalRecordSet` or `SessionCompleted` and delivers per user preference.

**Events emitted:** `ActivityIngested` → `WorkoutCreated` → (`PersonalRecordSet`, `SessionCompleted`).

**BCs involved:** all six.

**Success criteria:** Within ~10 seconds of webhook receipt, the workout is visible in the user's catalog and analytics are updated.

---

## UC-4: User disconnects Strava (or Strava revokes token)

**Trigger:** Either (a) the user clicks "Disconnect" in Aperitivo, or (b) the user revokes Aperitivo access in Strava settings, or (c) any Strava API call returns 401/invalid_token, or (d) Strava sends a deauthorization webhook (`aspect_type: "update", updates.authorized: "false"`).

**Flow:**
1. Detection point varies (in-app `DELETE /api/me/sources/strava`, webhook, or API error) and determines the `RevocationReason`.
2. IAM marks `ConnectedSource.status = REVOKED`.
3. IAM emits `IntegrationRevoked`.
4. Activity Ingestion consumes it and cancels pending sync jobs for this user.
5. Notifications consumes it and sends "your Strava connection is broken, please reconnect" via email + in-app — for the user-initiated case this is suppressed, since the user knows.

**BCs involved:** Identity & Access, Activity Ingestion, Notifications.

**Success criteria:** No further Strava API calls are attempted for this user; the user is notified within minutes.

**The account survives.** Revocation removes a *data source*, not an identity: the user still signs in with Google exactly as before, keeps every workout already imported, and can reconnect (UC-1b) at any time. Under the previous Strava-login model, losing the Strava connection meant losing the only way in — which is why in-app disconnect could not be offered at all. See [ADR 0011](../adr/0011-identity-provider-signup.md).


---

## UC-5: User views fitness trend

**Trigger:** User navigates to the "Performance" view in the client.

**Flow:**
1. Client calls `GET /api/athlete/fitness?from=...&to=...`.
2. Performance Analytics returns a time-series of CTL/ATL/TSB from TimescaleDB.

**BCs involved:** Performance Analytics.

**Success criteria:** Query returns within 200ms for a 6-month window.

---

## UC-6: User creates a training plan

**Trigger:** User builds or imports a training plan.

**Flow:**
1. Client `POST /api/plans` with the plan definition.
2. Planning BC persists the plan and its `ScheduledSession`s.
3. Each scheduled session becomes notification-eligible based on user preferences.

**BCs involved:** Training Planning, Notifications.

---

## UC-7: Scheduled session reminder

**Trigger:** A `ScheduledSession` becomes due (e.g. 1 hour before scheduled start time).

**Flow:**
1. Planning BC scheduler fires `ScheduledSessionDue`.
2. Notifications consumes it and delivers a reminder (in-app via SSE if the user is connected, plus email per preference).

**BCs involved:** Training Planning, Notifications.

---

## UC-8: Periodic reconciliation sync

**Trigger:** Scheduled job runs every N hours per user.

**Purpose:** Catch activities missed due to dropped Strava webhooks.

**Flow:**
1. Scheduler iterates `ConnectedSource`s with `status = ACTIVE`.
2. For each, calls Strava `GET /athlete/activities?after={last_synced_at}` (the SyncCursor watermark, stored on the source's `SyncState` row).
3. For each missed activity, creates a `SyncJob` (same path as UC-3 from step 3 onward).
4. Updates the `SyncCursor`.

**BCs involved:** Activity Ingestion → cascade as in UC-3.

---

## UC-9 (Future, out of MVP): Coach views athletes

Reserved for the post-MVP coach use case. Anticipated but not designed yet.

---

## Mapping to architecture

This table cross-references use cases to the BCs and events they exercise, used to validate that the BC decomposition is sound (no BC exists without being exercised by a use case; no use case requires cross-BC coupling beyond defined events).

| UC | IAM | Ingestion | Catalog | Analytics | Planning | Notifications |
|---|---|---|---|---|---|---|
| UC-1 First sign-up | ★ | | | | | |
| UC-1b Connect Strava | ★ | ○ | | | | |
| UC-2 Returning login | ★ | | | | | |
| UC-3 Webhook ingestion | | ★ | ★ | ★ | ★ | ★ |
| UC-4 Revocation | ★ | ★ | | | | ★ |
| UC-5 View fitness | | | | ★ | | |
| UC-6 Create plan | | | | | ★ | |
| UC-7 Session reminder | | | | | ★ | ★ |
| UC-8 Reconciliation | | ★ | (cascade) | (cascade) | (cascade) | (cascade) |

★ = primary owner   ○ = participates asynchronously

Note that UC-1 now touches **only** IAM: signing up starts no ingestion, because there is nothing to ingest yet. Ingestion enters at UC-1b, when a source is actually connected.

