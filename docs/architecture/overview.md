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
- OAuth2 login and self-issued JWT identity with Spring Security
- Full-stack observability with the OpenTelemetry stack
- Bounded Context decomposition with explicit, documented rationale

## Non-goals

Explicitly **out of scope**, even though they would be reasonable features for an "athlete platform":

- **Realtime live-streaming of in-progress workouts.** Strava webhooks fire post-activity only — there is no public API for live HR/pace/GPS streams. We do not attempt to fake this. See [ADR 0001](../adr/0001-no-strava-live-streaming.md).
- **Social features: friend graph, public challenges, leaderboards, comments, likes.** Strava already does these well. Competing on social is out of scope. See [ADR 0006](../adr/0006-no-social-challenges.md).
- **Multi-source data ingestion (Garmin, Wahoo, COROS) in MVP.** The architecture is designed to allow this later without painful migration, but MVP ships Strava-only.
- **Alternative identity providers (Google, Apple, email) in MVP.** Same as above — model supports adding them later, MVP is Strava-only.
- **Mobile or web frontend.** This is a **backend** project. Any UI demos are minimal and exist only to exercise APIs.

## Architectural style

Aperitivo is a **modular monolith** decomposed into Bounded Contexts. Each BC is a module with explicit public interfaces (APIs and events) and private internals (domain model, persistence, internal services). Module boundaries are enforced at build time by Spring Modulith.

The code style is **Spring layered**, not tactical DDD: entities are plain data (POJOs/records/Lombok, no behavior), services hold all logic (transactions, validation, event publication), repositories are Spring Data JPA. We keep DDD's **strategic** patterns — bounded contexts, ubiquitous language, domain events — and deliberately reject the **tactical** rich-domain-model pattern.

Inter-BC communication is **event-driven** via Spring Modulith's in-process application events (persisted in the `event_publication` table, published after commit). There is no external message broker in the MVP. See [ADR 0008](../adr/0008-event-transport.md). Synchronous read-side queries between BCs go through published in-process interfaces.

See [Bounded Contexts](bounded-contexts.md) for the full decomposition.

## Technology stack

| Layer | Choice | Why |
|---|---|---|
| Language | Java 21 | Virtual threads, pattern matching, records |
| Framework | Spring Boot 4, Spring Modulith | Modular monolith with enforced boundaries and in-process events |
| Persistence | PostgreSQL + TimescaleDB extension | Single OLTP DB for most BCs; TimescaleDB hypertables for time-series metrics in Analytics |
| Inter-BC events | Spring Modulith application events (`event_publication` table) | Durable, transactional, in-process; no broker. Kafka-ready design, externalized later if needed |
| Cache / locks | Redis | Hot data, rate-limit counters, distributed locks, optional `jti` blacklist |
| Identity | Spring Security 6 (OAuth2 client + self-issued JWT, RS256) | Strava OAuth login; we mint our own OIDC-style JWT. No external identity server |
| Observability | OpenTelemetry, Prometheus, Grafana, Loki, Tempo | Traces, metrics, logs unified |
| API | REST (Spring MVC), SSE for server push | No GraphQL, no WebSocket |
| Frontend (demo) | Thymeleaf + HTMX | Minimal UI to exercise APIs; backend is the focus |
| Build | Maven (multi-module) | One module per BC |
| Containers | Docker Compose for local dev | No Kubernetes for MVP |

See [ADR 0007](../adr/0007-sse-not-websocket.md) for SSE-over-WebSocket rationale, [ADR 0008](../adr/0008-event-transport.md) for the no-Kafka decision, and [ADR 0009](../adr/0009-identity-spring-security.md) for the Spring Security identity decision.

## Deployment model

- **Local development:** Docker Compose. A single `docker-compose.yml` brings up the application + Postgres+TimescaleDB + Redis + the OTel collector + Grafana stack.
- **Production:** A VPS deployment is the initial target (invite-only launch). The architecture is 12-factor / K8s-ready (externalized config and state) but Kubernetes is not used for the MVP.

## Key constraints

- **Strava is the single point of dependency.** If Strava revokes API access or significantly changes its terms, the product becomes non-functional. Accepted risk for a portfolio project.
- **Strava rate limits:** 100 requests per 15 minutes, 1000 per day per access token. All ingestion logic must respect this. This — not JVM throughput — is the real bottleneck. See [Strava API quirks](../integrations/strava/api-quirks.md).
- **Webhooks are unreliable:** Strava webhooks can be lost. Reconciliation via periodic sync is mandatory, not optional.
- **Refresh tokens are long-lived but revocable:** Token lifecycle management is a first-class concern. See [ADR 0003](../adr/0003-token-storage-strategy.md).
