# Roadmap

This document describes the staged delivery plan for Aperitivo. It is **not** a marketing roadmap — it's the engineering sequence we expect to follow, with clear "exit criteria" for each phase.

The MVP target is a single-VPS, invite-only deployment. Phasing reflects that: get one user (the author) through the full pipeline first, then widen.

## Phase 0 — Foundations

Goal: project skeleton, infrastructure, walking skeleton.

- Maven multi-module project with one module per BC
- Spring Boot 4 + Spring Modulith setup
- Docker Compose with Postgres+TimescaleDB, Redis, OTel collector, Grafana stack
- CI: build + Spring Modulith architecture tests (`ApplicationModules.verify()`) + unit tests
- Database migrations per BC (Liquibase or Flyway)
- Health endpoints, basic Actuator config
- Initial documentation skeleton (this repo)

**Exit:** "hello world" end-to-end — a request enters the system, traverses one module, returns. All infra services run with `docker compose up`.

---

## Phase 1 — Identity & Access

Goal: full Strava sign-in flow.

- `User` and `ConnectedSource` schemas, repositories, encryption-at-rest converter
- Strava OAuth client via Spring Security (authorize, callback, code exchange, refresh)
- Self-issued JWT (Spring Security `JwtEncoder`, RS256); resource-server validation
- `TokenManager` component with concurrency-safe refresh
- IAM REST endpoints: initiate Strava login, callback, current user info
- Deauthorization webhook handler
- `UserRegistered`, `IntegrationConnected`, `IntegrationRevoked` events published in-process

**Exit:** A user can sign in via Strava, receives a valid Aperitivo JWT, and has a `ConnectedSource` row with encrypted tokens. Logging out and revoking on the Strava side correctly mark the source as `REVOKED`.

---

## Phase 2 — Activity Ingestion

Goal: reliable post-activity ingestion.

- Webhook receiver with verification
- `SyncJob` queue with retry/backoff
- Initial backfill on first sign-up (paginated, rate-limit-aware)
- Periodic reconciliation scheduler
- `RawActivityPayload` retention
- `ActivityIngested` event published

**Exit:** Activities created on Strava appear in Aperitivo's raw payload table within seconds. Webhooks lost in transit are caught by reconciliation within the next sync window. The rate-limit budget is never exceeded.

---

## Phase 3 — Workout Catalog

Goal: canonical Workout model.

- `Workout` aggregate with `Sport` discriminator (tiers 1–2: summary + laps/segment-efforts/best-efforts)
- Bounded `@OneToMany` child collections inside the aggregate; route as encoded `mapPolyline`
- Normalization logic from raw Strava payload to canonical model
- REST endpoints for listing (projection) and detail (entity graph)
- `WorkoutCreated`, `WorkoutUpdated`, `WorkoutDeleted` events

> Tier-3 per-second streams are NOT in Catalog — they land in Performance Analytics (Phase 4).

**Exit:** Every `ActivityIngested` event results in a persisted `Workout` and a `WorkoutCreated` event. Workouts are queryable by user, date range, and sport.

---

## Phase 4 — Performance Analytics

Goal: derived metrics with TimescaleDB.

- TimescaleDB hypertable (`activity_samples`) for tier-3 per-second streams (bulk insert)
- Derived relational tables: daily training load (CTL/ATL/TSB), personal records, power curve
- Recomputation pipeline triggered by `WorkoutCreated`/`WorkoutUpdated`/`WorkoutDeleted`
- Training-load computation per sport (power→TSS, HR→TRIMP, duration fallback)
- Personal record detection
- REST endpoints for fitness/form curves, power curves, and PRs
- `PersonalRecordSet` event (the only event Analytics publishes)

> Trend detection (multi-week monotonic CTL moves) is post-MVP — it would add a new event then.
> CTL/ATL/TSB and curves are read via the API, not broadcast as events.

**Exit:** A user with backfilled history sees correct CTL/ATL/TSB charts. New workouts shift the curves promptly, and a new best emits `PersonalRecordSet`.

---

## Phase 5 — Training Planning

Goal: planned workouts and planned-vs-actual.

- `Plan`, `ScheduledSession`, `PlannedTarget` schemas
- Plan creation endpoints
- Planned-vs-actual matching logic
- Daily scheduler that fires `ScheduledSessionDue`
- `SessionCompleted`, `SessionMissed` events

**Exit:** A user can create a plan, see scheduled sessions on a calendar, and after completing a workout see the matched-vs-planned comparison.

---

## Phase 6 — Notifications

Goal: deliver events as user-facing messages.

- `Notification`, `Template`, `Preference` schemas
- Per-event-type subscription model
- Email channel (SMTP)
- SSE channel for in-app push (in-memory emitter registry)
- Delivery status tracking, retry on failure

**Exit:** All relevant domain events from earlier phases reach the user through their configured channels.

---

## Phase 7 — Observability hardening

Goal: production-grade telemetry.

- Full OTel instrumentation across all BCs (traces span in-process event boundaries)
- Custom metrics (Strava rate-limit utilization, ingestion lag, ConnectedSource health)
- Grafana dashboards per BC
- Alert rules (token failures, ingestion lag, error rates)

**Exit:** From a single Grafana dashboard, the health of the entire system is observable. A failing user integration is detectable within one minute.

---

## Post-MVP candidates (not committed)

- Additional identity providers (Google, Apple, email)
- Additional data sources (Garmin Connect, Wahoo, COROS, Suunto, Polar, TrainingPeaks)
- Coach use case (one user views multiple athletes)
- Production hardening: envelope encryption (Vault Transit / KMS), Redis access-token cache
- Externalized events (broker) if a cross-process consumer appears — see [ADR 0008](../adr/0008-event-transport.md)
- Race readiness predictor
- Power/pace curve analytics
- Strava segment efforts tracking
- Mobile app
