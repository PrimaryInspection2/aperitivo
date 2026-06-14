# Use Cases

User-facing scenarios that Aperitivo supports. Used to validate that the architecture serves real product needs, and as input to API and event design.

Format: each use case has a **trigger**, **actors**, **flow**, and **success criteria**. Linked to the BCs and events involved.

---

## UC-1: First-time sign-up via Strava

**Trigger:** New user visits Aperitivo and clicks "Sign in with Strava".

**Actors:** User, Aperitivo backend, Strava OAuth.

**Flow:**
1. User clicks "Sign in with Strava".
2. Aperitivo (Spring Security OAuth2 client) redirects to Strava's OAuth authorize URL with our `client_id` and requested scopes (`read,activity:read_all,profile:read_all`).
3. User authorizes Aperitivo on Strava.
4. Strava redirects back with an authorization code.
5. Aperitivo exchanges the code for access + refresh tokens.
6. Aperitivo creates an internal `User` (UUID) if no existing user maps to this `strava_athlete_id`.
7. Aperitivo creates a `ConnectedSource` with encrypted tokens.
8. Aperitivo issues its own JWT for the internal user via Spring Security (`JwtEncoder`, RS256) and returns it to the client.
9. User is redirected to Aperitivo holding the JWT.
10. **Asynchronously:** Activity Ingestion BC starts the initial backfill of the user's activity history.

**Events emitted:** `UserRegistered`, `IntegrationConnected`.

**BCs involved:** Identity & Access (primary), Activity Ingestion (background backfill kick-off).

**Success criteria:** User holds a valid JWT, has a `ConnectedSource` with `ACTIVE` status, and sees a "we're syncing your history" UI state.

---

## UC-2: Returning user logs in

**Trigger:** Existing user clicks "Sign in with Strava".

**Flow:** Same as UC-1 except step 6 finds an existing User and step 10 (backfill) is skipped or limited to recent activities.

**BCs involved:** Identity & Access.

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
8. Workout Catalog consumes it, normalizes the payload, persists a `Workout`, emits `WorkoutPublished`.
9. Performance Analytics consumes `WorkoutPublished`, recomputes derived metrics, possibly emits `PersonalRecordDetected`.
10. Training Planning consumes `WorkoutPublished`, attempts to match against a pending `ScheduledSession`.
11. Notifications consumes any `PersonalRecordDetected` or `SessionCompleted` and delivers per user preference.

**Events emitted:** `ActivityIngested` → `WorkoutPublished` → (`MetricsRecomputed`, `PersonalRecordDetected`, `SessionCompleted`).

**BCs involved:** all six.

**Success criteria:** Within ~10 seconds of webhook receipt, the workout is visible in the user's catalog and analytics are updated.

---

## UC-4: User disconnects Strava (or Strava revokes token)

**Trigger:** Either (a) the user revokes Aperitivo access in Strava settings, or (b) any Strava API call returns 401/invalid_token, or (c) Strava sends a deauthorization webhook (`aspect_type: "update", updates.authorized: "false"`).

**Flow:**
1. Detection point varies (webhook, API error, or user-initiated).
2. IAM marks `ConnectedSource.status = REVOKED`.
3. IAM emits `IntegrationRevoked`.
4. Activity Ingestion consumes it and cancels pending sync jobs for this user.
5. Notifications consumes it and sends "your Strava connection is broken, please reconnect" via email + in-app.

**BCs involved:** Identity & Access, Activity Ingestion, Notifications.

**Success criteria:** No further Strava API calls are attempted for this user; the user is notified within minutes.

---

## UC-5: User views fitness trend

**Trigger:** User navigates to the "Performance" view in the client.

**Flow:**
1. Client calls `GET /api/analytics/fitness?from=...&to=...`.
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
2. For each, calls Strava `GET /athlete/activities?after={SyncCursor.last_synced_at}`.
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
| UC-1 First sign-up | ★ | ○ | | | | |
| UC-2 Returning login | ★ | | | | | |
| UC-3 Webhook ingestion | | ★ | ★ | ★ | ★ | ★ |
| UC-4 Revocation | ★ | ★ | | | | ★ |
| UC-5 View fitness | | | | ★ | | |
| UC-6 Create plan | | | | | ★ | |
| UC-7 Session reminder | | | | | ★ | ★ |
| UC-8 Reconciliation | | ★ | (cascade) | (cascade) | (cascade) | (cascade) |

★ = primary owner   ○ = participates asynchronously
