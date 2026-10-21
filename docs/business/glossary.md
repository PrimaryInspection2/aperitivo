# Glossary — Ubiquitous Language

This glossary defines the **ubiquitous language** of Aperitivo: terms that have a precise meaning inside the system and must be used consistently in code, documentation, and conversation.

DDD principle: when the same word means different things in different Bounded Contexts, it is listed once per BC.

> Style note: Aperitivo uses DDD **strategic** patterns (bounded contexts, ubiquitous language, domain events) but a **Spring layered** code style — entities are data, services hold behavior. "Entity" below means a data record managed by a service, not a behavior-rich aggregate.

---

## Cross-cutting terms

**Aperitivo** — the platform itself. Always capitalized.

**Athlete** — a person who trains. Used as a domain-flavored synonym for "user" when speaking about training context (e.g. "the athlete's fitness trend"). In code and identity contexts, `User` is the canonical term.

**MVP** — Minimum Viable Product. The first deliverable scope, deliberately bounded. Anything labeled "future" or "deferred" is post-MVP.

**Bounded Context (BC)** — a module of the modular monolith with its own ubiquitous language, domain model, and data ownership. The six BCs are: Identity & Access, Activity Ingestion, Workout Catalog, Performance Analytics, Training Planning, Notifications.

**Domain Event** — an immutable fact about something that has happened. Past tense. Published by one BC, consumed by zero or more other BCs. Delivered in-process via Spring Modulith (no broker in MVP).

---

## Identity & Access

**User** — the internal identity record. Has a UUID `user_id` that is the stable handle throughout the system. Decoupled from any external system's user ID.

**UserIdentity** — a way a User can sign in: the pair `(identity provider, sub)` released by an OIDC provider. Holds **no tokens**, **no email**, and **no status lifecycle** — a sign-in method is present or absent, and everything about the *person* (contact address, display name, avatar) lives on `User`. A User has 1 or more UserIdentities and can never have zero (removing the last one would leave the account unreachable). MVP: exactly 1, Google. See [ADR 0011](../adr/0011-identity-provider-signup.md).

**ConnectedSource** — a credentialed link from a User to an external data provider (currently Strava). Each ConnectedSource has its own lifecycle (`ACTIVE`, `REVOKED`, `ERROR`) and holds the OAuth tokens for that provider. A User has **0 or more** ConnectedSources — zero is the normal state of a freshly signed-up account, and the permanent state of a user who never connects anything.

**Identity Provider (IdP)** — an external system that **authenticates** the User and tells us who they are. MVP: `GOOGLE` (OIDC). Future: Apple, Facebook. Produces a `UserIdentity`. Identity *tokens* are issued by Aperitivo itself (Spring Security), not by an external identity server — the IdP's ID token establishes who the user is, once, at login; we mint the access and refresh tokens.

**Provider** — an external system Aperitivo integrates with as an OAuth-based, webhook-style **data source**. MVP: `STRAVA`. Realistic future candidates with similar token semantics: `GARMIN`, `WAHOO`, `COROS`, `SUUNTO`, `POLAR`, `TRAININGPEAKS`. Produces a `ConnectedSource`. (Mobile-native sources like Apple Health / Google Fit and file uploads like FIT/GPX/TCX have different semantics and are not modeled as Providers.)

> **Identity Provider and Provider are different things and are never mixed.** An IdP answers *"who is this?"*; a Provider answers *"where does their training data come from?"*. Strava is a Provider and **not** an IdP — it is plain OAuth 2.0 with no ID token and no email in the default scope, so it cannot establish an account identity. They are modeled as two enums (`IdentityProvider`, `Provider`) and two tables (`user_identities`, `connected_sources`) precisely so the compiler and the schema keep them apart. See [ADR 0011](../adr/0011-identity-provider-signup.md).

**Login flow** — the flow that **signs a user in**, run by an *anonymous* caller: the client obtains a Google **ID token** itself (Google Identity Services on the web, the native SDK on mobile) and exchanges it at `POST /api/auth/google`; IAM verifies it, resolves or creates the `User` and `UserIdentity`, and issues an access token and a refresh token. No server-side redirect and no session. Never touches `connected_sources`. See [ADR 0012](../adr/0012-spa-mobile-stateless-api.md).

**Connect flow** — the OAuth authorization-code flow that **attaches a data source**, run against a Provider by an *already-authenticated* caller. **Client-driven:** the SPA sends the user to the Provider, receives the callback on its own route and verifies `state`; it then posts the code to `POST /api/connect/{provider}` with its access token, and IAM exchanges the code server-side with the `client_secret`. Creates or reactivates a `ConnectedSource`. Never signs anyone in and never changes who is logged in — the `user_id` comes from our own access token. See [ADR 0012](../adr/0012-spa-mobile-stateless-api.md).

**TokenManager** — the single component in the IAM BC with permission to read, refresh, and invalidate Strava (and future provider) tokens. No other code in the system touches encrypted token storage directly. Identity Providers contribute no tokens, so nothing in `user_identities` passes through it.

**UserContactReader** — IAM's published port for the account's contact address (`users.email`), read by the Notifications BC only, at the moment of email delivery. Narrow and read-only (`Optional<String> emailFor(UUID userId)`); returns empty for an account that has no address, which is a supported state rather than an error. The address is never carried on event payloads and never copied into another BC's schema.

**Revocation** — a state in which a ConnectedSource is no longer usable. Triggered by the Strava API returning 401, by Strava's deauthorization webhook, or by the user disconnecting the source in-app. Causes the `IntegrationRevoked` event. Revoking a source never affects the user's ability to log in.

**JWT** — the format of the **access token** Aperitivo issues for an authenticated user (Spring Security `JwtEncoder`, RS256). Claims are minimal: `sub` = `user_id`, `iss`, `aud`, `iat`, `exp`, `jti`. Contains no provider identifiers and no Strava tokens. Note that `sub` is overloaded in the wider OAuth world: **our** JWT's `sub` is the internal `user_id`; a **provider's** `sub` is stored as `provider_user_id`. They are never the same value.

**Access token** — our short-lived (15-minute) JWT, returned in the JSON body of login and refresh and sent on every API call as `Authorization: Bearer`. Validated by signature alone, with no lookup; the SPA keeps it in memory. Not to be confused with a Provider's access token (Strava's, held by `TokenManager`) or a Google token, which is never accepted by our API. See [ADR 0013](../adr/0013-access-and-refresh-tokens.md).

**Refresh token** — an opaque random value whose only use is to obtain a new access token at `POST /api/auth/refresh`. Stored by IAM only as a hash; on the web it travels in an `HttpOnly` cookie scoped to `/api/auth`. **Rotated** on every use: the presented token is spent and a new one issued. Distinct from a Provider's refresh token (Strava's, held by `TokenManager`). See [ADR 0013](../adr/0013-access-and-refresh-tokens.md).

**Token family** — the chain of refresh tokens descending from one sign-in on one device. Presenting an already-spent token from a family (reuse) revokes the whole family; logout revokes the current family, "log out everywhere" all of the user's families. A family has an absolute lifetime, after which the user signs in again. See [ADR 0013](../adr/0013-access-and-refresh-tokens.md).


---

## Activity Ingestion

**WebhookEvent** — an incoming notification from Strava. Has shape `{ aspect_type, object_type, object_id, owner_id, updates }`. Carries no actual activity data — just identifiers. Persisted verbatim as a durable inbox row.

**RawActivityPayload** — the JSON returned by Strava's `GET /activities/{id}` endpoint, stored verbatim. Retained as the **replay source of truth**: Catalog re-normalizes from it and Analytics re-materializes streams from it, without re-calling Strava.

**SyncJob** — a unit of ingestion work. Triggered by a webhook, by a periodic reconciliation, or by an initial backfill — all three converge here. Has retry semantics and a status machine.

**SyncState** — the per-`(user_id, provider)` record carrying sync progress; persisted as `sync_state`. Its watermark fields are the **SyncCursor**.

**SyncCursor** — the watermark (on the `SyncState` row) indicating the last successfully synced point (`last_synced_at` + `last_synced_activity_id` tie-break). Used by reconciliation to detect missed webhooks.

**Idempotency Key** — the combination `(provider, provider_activity_id, aspect_type)` used to deduplicate webhook deliveries (Strava can deliver the same event multiple times). Enforced as a unique constraint on `sync_jobs`.

**Rate Limit Budget** — the count of remaining Strava API calls in the current 15-minute and 24-hour windows. Tracked in Redis (not relational state). The Ingestion BC must respect this. This is the system's real throughput bottleneck. See [strava-rate-limits.md](../technical-notes/strava-rate-limits.md).

**Activity** — in this BC only, refers to the *raw, Strava-shaped* representation of a workout. Once archived and emitted as `ActivityIngested`, it leaves Ingestion's world and becomes a `Workout` in Catalog.

---

## Workout Catalog

**Workout** — the canonical, sport-aware representation of a completed training session in Aperitivo. The aggregate root of this BC. Identified by an Aperitivo-internal UUID. Source of truth for "what happened and how it was structured." Holds **tiers 1–2** only (summary + bounded structured collections); per-second telemetry (tier 3) lives in Performance Analytics.

Note: a `Workout` corresponds 1:1 to a Strava activity in MVP, but the model does not assume this — it is provider-agnostic.

**SportType** — the enum discriminating the kind of activity. MVP values: `RUN`, `TRAIL_RUN`, `RIDE`, `GRAVEL_RIDE`, `MOUNTAIN_BIKE`, `SWIM`, `OPEN_WATER_SWIM`, `WORKOUT`, `HIKE`, `WALK`, `OTHER`. Unknown Strava values map to `OTHER` rather than failing normalization.

**Lap** — a tier-2 sub-segment of a Workout (a lap/interval as recorded by Strava), with its own distance, moving time, average power/HR, and `start_index`/`end_index` offsets into the activity's stream. Modeled as an `@OneToMany` child inside the `Workout` aggregate. (Replaces the earlier "Split" term.)

**SegmentEffort** — a tier-2 record of the athlete's effort over a Strava segment within a Workout: `segmentId` (a plain id-reference to Strava's global segment, **not** a modeled `Segment` aggregate in MVP), elapsed time, distance, and PR rank if applicable. An `@OneToMany` child of the aggregate.

**BestEffort** — a tier-2 best-effort record within a Workout (e.g. fastest `5k`, `1k`) as provided by Strava. An `@OneToMany` child of the aggregate.

**Route (polyline)** — the GPS track of a Workout, stored as Strava's **encoded polyline string** (`mapPolyline`) on the `Workout`. Whole-read for map rendering; it is *not* modeled as a per-point relational collection, and the numeric per-second streams live in Analytics, not here.

> Not part of this BC's language: "Split" (use `Lap`), "Stream" (tier-3, owned by Performance Analytics — see below), "Effort" (use the concrete `SegmentEffort` / `BestEffort`).

---

## Performance Analytics

**Stream** — a time-series of one channel (heart rate, power, cadence, velocity, altitude) sampled second-by-second, 10³–10⁴ samples per channel per activity. **Owned by Performance Analytics** (tier-3), stored in the TimescaleDB `activity_samples` hypertable via bulk insert — never as a JPA collection. Sourced from `RawActivityPayload`. (The GPS lat/lon track is the exception: it stays in Catalog as an encoded polyline.)

**activity_samples** — the TimescaleDB hypertable holding per-second `Stream` data, partitioned on time, bulk-inserted, queried with `time_bucket`/aggregates.

**Training Load (TSS)** — Training Stress Score. A single number summarizing how taxing a single workout was. Computed from the streams: power→TSS (cycling), HR-based TRIMP (most sports), duration×intensity fallback. Summed per user per day as the input to CTL/ATL.

**Fitness (CTL)** — Chronic Training Load. A ~42-day exponentially weighted moving average of daily training load. Proxies long-term fitness.

**Fatigue (ATL)** — Acute Training Load. A ~7-day exponentially weighted moving average of daily training load. Proxies short-term fatigue.

**Form (TSB)** — Training Stress Balance. `CTL − ATL`. Positive (CTL > ATL) = rested; negative = currently overloaded. Used to gauge race-day readiness.

**Personal Record (PR)** — best-ever performance over a specified distance/duration/sport. E.g. fastest 5km run, highest 20-minute average power, longest ride. Stored in `personal_records`; a new best emits `PersonalRecordSet`.

**Curve** — a mean-max relationship over duration, computed from the streams: the power curve (best average power for every duration from 1s to hours) and its pace equivalent. Stored in `activity_power_curve` and served via the API. (In MVP — the TimescaleDB schema is built for it.)

**Recompute-forward-from-date** — the rule that an updated or deleted workout changes its day's training load and therefore every CTL/ATL/TSB value from that date onward; Analytics recomputes the affected tail. The reason Catalog splits create/update/delete into distinct events.

**Trend** — *(post-MVP)* a detected directional change in a derived metric over a time window (e.g. "fitness rising for 3 weeks"). Not built in MVP; would introduce a new event when added.

---

## Training Planning

**Plan** — a multi-week structured program of `ScheduledSession`s.

**ScheduledSession** — a planned workout for a specific date. Has `PlannedTarget`s (e.g. "60 minutes Z2 run, 8 km").

**PlannedTarget** — a specific intended attribute of a scheduled session (distance, duration, pace zone, power zone, TSS). A TSS-valued target stores a **planned number Planning owns** (authoring intent); MVP compliance is computed from directly-readable workout dimensions and does **not** read Analytics' actual TSS — so there is no cross-BC coupling. See the [Planning domain-model](../contexts/training-planning/domain-model.md).

**Compliance** — how well the actual completed workout matched the planned session. Computed as a similarity score plus per-target deltas.

**Match** — the link between a `ScheduledSession` and the `Workout` that fulfilled it. Established by date proximity + sport + target similarity. May be a manual override.

---

## Notifications

**Notification** — a single user-facing message, addressed to one User, derived from a domain event from another BC.

**Channel** — a delivery medium: `EMAIL`, `IN_APP` (via SSE). (`PUSH` is deferred.)

**Template** — a parameterized message body keyed by event type and channel.

**Preference** — per-user configuration of which event types deliver to which channels.

**DeliveryAttempt** — a single attempt to send one Notification through one Channel. Has status (`QUEUED`, `SENT`, `FAILED`, `RETRYING`) and a retry counter.

**SseEmitter** — the Spring MVC construct holding one user's open SSE connection.
