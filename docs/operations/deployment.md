# Operations: Deployment

How Aperitivo is deployed — the Docker Compose topology for local dev and the single-VPS production
target, the configuration and environment variables, database migration handling, and startup
concerns. Deployment model context: [architecture overview](../architecture/overview.md).

## Deployment model

- **Local development & MVP production:** **Docker Compose** on a single host. One
  `docker-compose.yml` brings up the application plus its backing services.
- **Production target:** a **single VPS**, invite-only launch. The architecture is **12-factor /
  K8s-ready** (externalized config and state, stateless app) but **Kubernetes is not used in MVP** —
  it would be a step up if/when horizontal scaling is needed ([overview](../architecture/overview.md)).

The app is a **modular monolith** — one deployable artifact ([ADR 0005](../adr/0005-bounded-contexts.md)),
so "deploy" is one container, not six services. This is the operational payoff of the
modular-monolith choice: BC boundaries are enforced at build time
([spring-modulith-boundaries.md](../technical-notes/spring-modulith-boundaries.md)), but runtime is a
single process.

## Compose topology

```
docker-compose.yml
├── app                  ← the Aperitivo modular monolith (Spring Boot 4)
├── postgres             ← PostgreSQL + TimescaleDB extension (one instance, per-BC schemas)
├── redis                ← rate-limit counters, jti denylist, locks
├── otel-collector       ← receives OTel signals from app, fans out to the three backends
├── prometheus           ← metrics
├── tempo                ← traces
├── loki                 ← logs
└── grafana              ← dashboards over Prometheus/Tempo/Loki
```

One Postgres instance hosts **all** BC schemas (`identity`, `ingestion`, `catalog`, `analytics`,
`planning`, `notifications`) plus TimescaleDB for Analytics' hypertable — logical separation by
schema, operational simplicity of one database ([ADR 0005](../adr/0005-bounded-contexts.md)). Future
extraction to separate DBs is mechanical (no cross-BC FKs to untangle).

### Service dependencies & startup order

- `app` depends on `postgres` and `redis` being healthy (Compose `depends_on` + healthchecks).
- The observability stack (`otel-collector`, `prometheus`, `tempo`, `loki`, `grafana`) can start
  independently; the app degrades gracefully if the collector is briefly unavailable (telemetry is
  best-effort, never on the request path).
- **Migrations run at app startup** (below), so `postgres` must be ready first.

## Configuration (12-factor)

All configuration is **externalized** — environment variables / mounted secrets, nothing
environment-specific baked into the image. Key groups:

```
# Strava integration
STRAVA_CLIENT_ID, STRAVA_CLIENT_SECRET
STRAVA_WEBHOOK_VERIFY_TOKEN          # the hub.verify_token for the subscription handshake

# Identity / crypto (env-sourced in MVP; KMS-backed in prod)
JWT_SIGNING_KEY                      # RS256 private key (PEM)
TOKEN_ENCRYPTION_KEY                 # AES-256 key for token columns

# Datastores
POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD
REDIS_URL

# App
APP_BASE_URL                         # public HTTPS base (callback + issuer URIs derive from this)
JWT_TTL                              # 24–72h

# Observability
OTEL_EXPORTER_OTLP_ENDPOINT          # → otel-collector
```

Secrets (`*_SECRET`, `*_KEY`, passwords) are injected at deploy time, never committed, never in the
image — the [security](security.md) posture. In MVP they're Compose env/secret files; in production
they come from a secret manager.

## Database migrations

- **Flyway, per-BC changesets**, run at **application startup** ([per-BC database docs]).
- Each BC owns its migration set under its module; the `CREATE EXTENSION timescaledb` +
  `create_hypertable` calls live in Analytics' first migration
  ([timescaledb-schema.md](../technical-notes/timescaledb-schema.md)).
- **Migrations never touch encryption keys** — they create `TEXT` columns; AES-GCM encryption is
  purely application-side ([encryption-at-rest.md](../technical-notes/encryption-at-rest.md)).
- Adding an enum value (sport type, status, channel) needs **no** migration — enums are stored as
  `TEXT` for forward-compatibility (every BC's database doc).

## Reverse proxy (nginx)

A reverse proxy terminates TLS and fronts the app. Two app-specific concerns:

- **SSE:** the `/api/sse/events` location must set `X-Accel-Buffering: no` and `proxy_buffering off`,
  and have read timeouts exceeding the heartbeat interval, or events buffer/stall
  ([sse-streaming.md](../technical-notes/sse-streaming.md)).
- **Webhook callback:** `POST /api/webhooks/strava` is a plain HTTPS POST (no buffering concern), but
  must be publicly reachable by Strava and respond within the ~2-second budget — so nothing slow sits
  in front of it ([webhooks.md](../integrations/strava/webhooks.md)).

## Health & readiness

- Spring Boot Actuator health endpoints (`/actuator/health` liveness + readiness), network-restricted
  (not public) ([security](security.md)).
- Readiness gates on Postgres + Redis connectivity; the app reports not-ready until migrations
  complete.
- The walking-skeleton exit of Phase 0 is "all infra up with `docker compose up`, a request traverses
  one module and returns" ([roadmap](../business/roadmap.md)).

## Scaling notes (deferred)

The MVP is **single-instance** by design, and two pieces of state assume it:

- **The SSE emitter registry** is in-memory (`Map<UserId, List<SseEmitter>>`) — multi-instance needs
  sticky sessions or Redis pub/sub fan-out ([sse-streaming.md](../technical-notes/sse-streaming.md)).
- Everything else (rate-limit counters, locks, jti denylist) is already in **Redis**, so it is
  multi-instance-ready — the deliberate reason those live in Redis and not in-process
  ([strava-rate-limits.md](../technical-notes/strava-rate-limits.md),
  [jwt-and-keys.md](../technical-notes/jwt-and-keys.md)).

So horizontal scaling is gated almost entirely by the SSE registry — a small, known piece of work,
not a rearchitecture. Kubernetes, a read replica for the hypertable, and broker-externalized events
([ADR 0008](../adr/0008-event-transport.md)) are the further-out scaling steps.

## What is deliberately NOT done (MVP)

- **No Kubernetes** — Compose on a VPS; K8s when scaling demands it.
- **No multi-instance app** — single instance; the SSE registry is the one blocker, documented.
- **No separate databases per BC** — one Postgres, per-BC schemas; extraction is mechanical later.
- **No blue-green / canary** — simple restart-on-deploy for MVP; zero-downtime deploy is a later
  concern.
- **No managed cloud services** — self-hosted Compose stack for the portfolio MVP.

## Related

- [architecture overview](../architecture/overview.md) — deployment model, stack, constraints
- [observability.md](observability.md) — the Grafana stack this deploys
- [security.md](security.md) — secrets injection, TLS, the secrets trajectory
- [roadmap Phase 0](../business/roadmap.md) — the walking-skeleton deployment exit
- [spring-modulith-boundaries.md](../technical-notes/spring-modulith-boundaries.md) — one artifact, enforced boundaries
