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

**ConnectedSource** — a credentialed link from a User to an external data provider (currently Strava). Each ConnectedSource has its own lifecycle (`ACTIVE`, `REVOKED`, `ERROR`) and holds the OAuth tokens for that provider. A User has 0 or more ConnectedSources (MVP: exactly 1, Strava).

**Provider** — an external system Aperitivo integrates with as an OAuth-based, webhook-style data source. MVP: `STRAVA`. Realistic future candidates with similar token semantics: `GARMIN`, `WAHOO`, `COROS`, `SUUNTO`, `POLAR`, `TRAININGPEAKS`. (Mobile-native sources like Apple Health / Google Fit and file uploads like FIT/GPX/TCX have different semantics and are not modeled as Providers.)

**Identity Provider (IdP)** — the system that authenticates the User. MVP: Strava (via OAuth2 login). Future: Google, Apple, email/password. Identity is issued by Aperitivo itself (Spring Security), not by an external identity server.

**TokenManager** — the single component in the IAM BC with permission to read, refresh, and invalidate Strava (and future provider) tokens. No other code in the system touches encrypted token storage directly.

**Revocation** — a state in which a ConnectedSource is no longer usable. Triggered by the Strava API returning 401, or by Strava's deauthorization webhook. Causes the `IntegrationRevoked` event.

**JWT** — the identity token Aperitivo issues for an authenticated user (Spring Security `JwtEncoder`, RS256). Claims are minimal: `sub` = `user_id`, `iss`, `iat`, `exp`, `jti`. Does not contain Strava tokens.

---

## Activity Ingestion

**WebhookEvent** — an incoming notification from Strava. Has shape `{ aspect_type, object_type, object_id, owner_id, updates }`. Carries no actual activity data — just identifiers.

**RawActivityPayload** — the JSON returned by Strava's `GET /activities/{id}` endpoint, stored verbatim. Retained for replay and debugging.

**SyncJob** — a unit of ingestion work. Triggered by a webhook, by a periodic reconciliation, or by an initial backfill. Has retry semantics.

**SyncCursor** — the watermark per `(user_id, provider)` indicating the last successfully synced activity. Used by reconciliation jobs to detect missed webhooks.

**Idempotency Key** — the combination `(provider, provider_activity_id, aspect_type)` used to deduplicate webhook deliveries (Strava can deliver the same event multiple times).

**Rate Limit Budget** — the count of remaining Strava API calls in the current 15-minute and 24-hour windows. Tracked per access token. The Ingestion BC must respect this. This is the system's real throughput bottleneck.

**Activity** — in this BC only, refers to the *raw, Strava-shaped* representation of a workout. Once normalized and emitted as `ActivityIngested`, it leaves Ingestion's world and becomes a `Workout` in Catalog.

---

## Workout Catalog

**Workout** — the canonical, sport-aware representation of a completed training session in Aperitivo. The root record of this BC. Identified by an Aperitivo-internal UUID. Source of truth for "what happened."

Note: a `Workout` corresponds 1:1 to a Strava activity in MVP, but the model does not assume this — it is provider-agnostic.

**Sport** — discriminator for sport-specific subtypes. MVP: `RUN`, `RIDE`, `SWIM`, `OTHER`. Each may have sport-specific metadata (e.g. `RUN.cadence`, `RIDE.power_avg`).

**Split** — a sub-segment of a Workout, typically per-km or per-mile, with its own duration, distance, pace, HR, etc.

**Stream** — a time-series of one channel (HR, power, pace, GPS lat/lon, elevation) sampled second-by-second. Bulk-stored, opaque to most consumers. Used by Analytics for curve computations.

**Effort** — *(future)* a segment-level performance within a Workout (e.g. Strava segment). Not in MVP.

---

## Performance Analytics

**Training Load (TSS)** — Training Stress Score. A single number summarizing how taxing a single workout was. Derived from duration × intensity. Different formulas per sport (rTSS for running, TSS for cycling, sTSS for swimming).

**Fitness (CTL)** — Chronic Training Load. A 42-day exponentially weighted moving average of TSS. Proxies long-term fitness.

**Fatigue (ATL)** — Acute Training Load. A 7-day exponentially weighted moving average of TSS. Proxies short-term fatigue.

**Form (TSB)** — Training Stress Balance. `CTL − ATL`. Positive (CTL > ATL) = rested; negative = currently overloaded. Used to gauge race-day readiness.

**Personal Record (PR)** — best-ever performance over a specified distance/duration/sport. E.g. fastest 5km run, highest 20-minute average power, longest ride.

**Trend** — a detected directional change in a derived metric over a time window (e.g. "fitness rising for 3 weeks").

**Curve** — *(future)* a relationship between two metrics, e.g. mean-max power curve, pace curve. Not in MVP scope but the TimescaleDB schema is designed for it.

---

## Training Planning

**Plan** — a multi-week structured program of `ScheduledSession`s.

**ScheduledSession** — a planned workout for a specific date. Has `PlannedTarget`s (e.g. "60 minutes Z2 run, 8 km").

**PlannedTarget** — a specific intended attribute of a scheduled session (distance, duration, pace zone, power zone, TSS).

**Compliance** — how well the actual completed workout matched the planned session. Computed as a similarity score plus per-target deltas.

**Match** — the link between a `ScheduledSession` and the `Workout` that fulfilled it. Established by date proximity + sport + target similarity. May be a manual override.

---

## Notifications

**Notification** — a single user-facing message, addressed to one User, derived from a domain event from another BC.

**Channel** — a delivery medium: `EMAIL`, `IN_APP` (via SSE). (`PUSH` is deferred.)

**Template** — a parameterized message body keyed by event type and channel.

**Preference** — per-user configuration of which event types deliver to which channels.

**DeliveryAttempt** — a single attempt to send one Notification through one Channel. Has status (`QUEUED`, `SENT`, `FAILED`, `RETRYING`) and a retry counter.

**SseEmitter** — the Spring MVC construct holding one user's open SSE connection. Held in an in-memory per-user registry in this BC.
