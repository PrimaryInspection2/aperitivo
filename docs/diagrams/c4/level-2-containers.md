# C4 Level 2 — Containers

The internal high-level deployment units of Aperitivo: the modular-monolith application and its
backing services. One level down from [System Context](level-1-system-context.md) — it opens the
Aperitivo box to show the *containers* (separately-runnable/deployable things), but not yet the
Bounded Contexts inside the app ([Level 3](level-3-components.md)).

C4 model: [c4model.com](https://c4model.com/). Deployment detail:
[deployment.md](../../operations/deployment.md).

## What this shows

Aperitivo is a **single application container** (the modular monolith) plus its datastores and the
observability stack — all on one host via Docker Compose for the MVP. The notable point: the app is
*one* deployable, not six services, even though it contains six Bounded Contexts (those are a
build-time/logical boundary, not separate containers —
[spring-modulith-boundaries.md](../../technical-notes/spring-modulith-boundaries.md)).

## Diagram

```mermaid
flowchart TB
    Athlete["👤 Athlete"]
    Strava["🏃 Strava (external)"]

    subgraph Host["Single VPS / Docker Compose"]
        NGINX["<b>nginx</b><br/><i>reverse proxy</i><br/>TLS termination, SSE buffering off"]

        APP["<b>Aperitivo App</b><br/><i>Spring Boot 4 modular monolith (Java 21)</i><br/>6 Bounded Contexts, in-process events<br/>REST API + SSE + webhook receiver"]

        PG["<b>PostgreSQL + TimescaleDB</b><br/><i>relational + time-series</i><br/>per-BC schemas; activity_samples hypertable;<br/>event_publication outbox"]
        REDIS["<b>Redis</b><br/><i>cache / counters / locks</i><br/>rate-limit counters, jti denylist,<br/>per-user refresh locks"]

        subgraph OBS["Observability stack"]
            OTEL["OTel Collector"]
            PROM["Prometheus<br/>(metrics)"]
            TEMPO["Tempo<br/>(traces)"]
            LOKI["Loki<br/>(logs)"]
            GRAF["Grafana<br/>(dashboards)"]
        end
    end

    Athlete -->|HTTPS| NGINX
    Strava -->|webhooks HTTPS| NGINX
    NGINX --> APP
    APP -->|OAuth/REST| Strava
    APP -->|JDBC| PG
    APP -->|RESP| REDIS
    APP -->|OTLP| OTEL
    OTEL --> PROM & TEMPO & LOKI
    GRAF --> PROM & TEMPO & LOKI
    APP -->|"email (SMTP)"| Athlete
    APP -->|"SSE"| NGINX
```

## The containers

| Container | Tech | Responsibility |
|---|---|---|
| **nginx** | reverse proxy | TLS termination; SSE buffering off (`X-Accel-Buffering: no`); fronts the webhook callback |
| **Aperitivo App** | Spring Boot 4, Java 21, Spring Modulith | the whole application — all six BCs, in-process events, REST + SSE + webhook receiver. One deployable |
| **PostgreSQL + TimescaleDB** | Postgres + extension | per-BC schemas in one instance; Analytics' `activity_samples` hypertable; the `event_publication` outbox |
| **Redis** | Redis | rate-limit counters, `jti` denylist, per-user refresh locks — the *multi-instance-ready* state |
| **OTel Collector + Prometheus + Tempo + Loki + Grafana** | OSS observability | metrics, traces, logs, dashboards ([observability.md](../../operations/observability.md)) |

## Key facts at this level

- **One app container, six BCs.** The Bounded Contexts are *logical* modules with build-time-enforced
  boundaries ([spring-modulith-boundaries.md](../../technical-notes/spring-modulith-boundaries.md)),
  not separate deployables. "Deploy" = one container restart. Extraction to services later is
  mechanical (no cross-BC FKs) — but explicitly not MVP.
- **One Postgres, many schemas.** All BCs share one Postgres instance with per-BC schemas + the
  TimescaleDB extension for Analytics. Logical separation, operational simplicity
  ([ADR 0005](../../adr/0005-bounded-contexts.md)).
- **No message broker.** Inter-BC events are in-process via the `event_publication` outbox in Postgres
  — no Kafka container ([ADR 0008](../../adr/0008-event-transport.md),
  [idempotency-and-outbox.md](../../technical-notes/idempotency-and-outbox.md)).
- **Redis holds the multi-instance-ready state.** Rate-limit counters, denylist, and locks live in
  Redis precisely so they're correct across instances — which is why horizontal scaling is gated only
  by the in-memory SSE registry, not these ([deployment.md](../../operations/deployment.md)).
- **No identity-server container.** Identity is in-app (Spring Security + own JWT) — no Keycloak
  ([ADR 0009](../../adr/0009-identity-spring-security.md)).

## Not shown here (deferred)

- The six BCs *inside* the app container and how they interact → [Level 3](level-3-components.md).
- Future scaling topology (multiple app instances, read replica, broker) — deferred
  ([deployment.md](../../operations/deployment.md)).

## Related

- [Level 1 — System Context](level-1-system-context.md)
- [Level 3 — Components](level-3-components.md)
- [deployment.md](../../operations/deployment.md), [observability.md](../../operations/observability.md)
- [architecture overview](../../architecture/overview.md)
```
