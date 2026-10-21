# Architecture Overview

## What is Aperitivo

Aperitivo is a backend platform for endurance athletes (running, cycling, triathlon) that extends Strava with deeper analytics, structured training planning, and richer post-activity insights. It is **not** a replacement for Strava — it consumes Strava as its primary data source and adds value on top.

Positioning: *"Strava tells you what you did. Aperitivo tells you what it means and what to do next."*

## Goals

### Product goals (MVP)
- Connect to an athlete's Strava account via OAuth and ingest their activities reliably
- Maintain a normalized, canonical workout model independent of Strava-specific formats
- Compute meaningful performance metrics over time (training load, fitness/fatigue/form, personal records, trends)
- Provide structured training planning with planned-vs-actual comparison
- Notify the athlete of relevant events (new PR, sync issues, scheduled session reminders, weekly summary)

### Engineering goals (portfolio)
This is a pet project intended to demonstrate **staff-level engineering**. It is designed to showcase:
- Event-driven architecture in a modular monolith (Spring Modulith, in-process events)
- Reliable integration with an external API that has rate limits, webhooks, and revocable OAuth tokens
- Time-series analytics with TimescaleDB
- OIDC identity-provider login (Google ID-token exchange) and self-issued JWT identity (15-minute access + rotating refresh) with Spring Security
- Full-stack observability with the OpenTelemetry stack
- Bounded Context decomposition with explicit, documented rationale

## Non-goals

Explicitly **out of scope**, even though they would be reasonable features for an "athlete platform":

- **Realtime live-streaming of in-progress workouts.** Strava webhooks fire post-activity only — there is no public API for live HR/pace/GPS streams. We do not attempt to fake this.
- **Social features: friend graph, public challenges, leaderboards, comments, likes.** Strava already does these well. Competing on social is out of scope.
- **Multi-source data ingestion (Garmin, Wahoo, COROS) in MVP.** The architecture is designed to allow this later without painful migration, but MVP ships Strava-only.
- **Alternative identity providers beyond Google (Apple, email) in MVP.** Login is Google OIDC only; the model supports adding Apple/email later. Strava is **not** a login provider — it is a data source connected from inside the app after signup ([ADR 0011](../adr/0011-identity-provider-signup.md)).
- **A polished frontend or a mobile app in MVP.** The backend is the focus. The web client is a React SPA consuming the API from the same origin, and a mobile app is a later client of the same API. Both are designed for, but their UI polish is not a goal. Until the SPA exists, the API is exercised with HTTP clients ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md)).

## Architectural style

Aperitivo is a **modular monolith** decomposed into Bounded Contexts. Each BC is a module with explicit public interfaces (APIs and events) and private internals (domain model, persistence, internal services). Module boundaries are enforced at build time by Spring Modulith.

The code style is **Spring layered**, not tactical DDD: entities are plain data (POJOs/records/Lombok, no behavior), services hold all logic (transactions, validation, event publication), repositories are Spring Data JPA. We keep DDD's **strategic** patterns — bounded contexts, ubiquitous language, domain events — and deliberately reject the **tactical** rich-domain-model pattern.

Inter-BC communication is **event-driven** via Spring Modulith's in-process application events (persisted in the `event_publication` table, published after commit). There is no external message broker in the MVP. See [ADR 0008](../adr/0008-event-transport.md). Synchronous read-side queries between BCs go through published in-process interfaces.

See [Bounded Contexts](bounded-contexts.md) for the full decomposition.

## Technology stack

| Layer | Choice | Why |
|---|---|---|
| Language | Java 25 | Virtual threads, pattern matching, records |
| Framework | Spring Boot 4, Spring Modulith | Modular monolith with enforced boundaries and in-process events |
| Persistence | PostgreSQL + TimescaleDB extension | Single OLTP DB for most BCs; TimescaleDB hypertables for time-series metrics in Analytics |
| Inter-BC events | Spring Modulith application events (`event_publication` table) | Durable, transactional, in-process; no broker. Kafka-ready design, externalized later if needed |
| Cache / locks | Redis | Hot data, rate-limit counters, distributed locks |
| Identity | Spring Security 7 (resource server + self-issued JWT, RS256) | The client obtains a Google ID token; the backend verifies it and mints a 15-minute access JWT plus a rotating refresh token ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md), [ADR 0013](../adr/0013-access-and-refresh-tokens.md)). One stateless security chain, no session. Strava is a connected data source, not a login provider ([ADR 0011](../adr/0011-identity-provider-signup.md)). No external identity server |
| Observability | OpenTelemetry, Prometheus, Grafana, Loki, Tempo | Traces, metrics, logs unified |
| API | REST (Spring MVC), SSE for server push | No GraphQL, no WebSocket |
| Frontend | React SPA, same origin behind a reverse proxy (`/` → SPA, `/api` → Spring) | Bearer-token client of the API; no CORS. Mobile later on the same API ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md)) |
| Build | Maven (multi-module) | One module per BC |
| Containers | Docker Compose for local dev | No Kubernetes for MVP |

See [ADR 0007](../adr/0007-sse-not-websocket.md) for SSE-over-WebSocket rationale, [ADR 0008](../adr/0008-event-transport.md) for the no-Kafka decision, [ADR 0009](../adr/0009-identity-spring-security.md) for the Spring Security identity decision, and [ADR 0012](../adr/0012-spa-mobile-stateless-api.md) / [ADR 0013](../adr/0013-access-and-refresh-tokens.md) for the client topology and token lifecycle.

## Deployment model

- **Local development:** Docker Compose. A single `docker-compose.yml` brings up the application + Postgres+TimescaleDB + Redis + the OTel collector + Grafana stack.
- **Production:** A VPS deployment is the initial target (invite-only launch). The architecture is 12-factor / K8s-ready (externalized config and state) but Kubernetes is not used for the MVP.
- **Single origin:** a reverse proxy serves the SPA at `/` and routes `/api/**` to the application, so the browser sees one origin: no CORS, and the refresh cookie can be `SameSite=Strict` ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md)). In development a Vite proxy reproduces the same layout. The application holds no HTTP session, so instances scale horizontally without sticky routing.

## Key constraints

- **Strava is the single point of dependency for data.** If Strava revokes API access or significantly changes its terms, the product becomes non-functional as a data platform. Accepted risk for a portfolio project. Login additionally depends on Google (the identity provider) — a distinct external party in the critical path of every sign-in ([ADR 0011](../adr/0011-identity-provider-signup.md)).
- **Strava rate limits:** 100 requests per 15 minutes, 1000 per day per access token. All ingestion logic must respect this. This — not JVM throughput — is the real bottleneck. See [Strava API quirks](../integrations/strava/api-quirks.md).
- **Webhooks are unreliable:** Strava webhooks can be lost. Reconciliation via periodic sync is mandatory, not optional.
- **Refresh tokens are long-lived but revocable:** Token lifecycle management is a first-class concern. See [ADR 0003](../adr/0003-token-storage-strategy.md).
