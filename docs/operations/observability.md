# Operations: Observability

How Aperitivo is instrumented and observed in production — the OpenTelemetry signal model, the key
metrics/traces/logs per BC, the dashboards, and the alert rules. This is the operational view; the
per-BC docs each have an "Observability" section listing their specific signals, which this note
consolidates.

Stack ([architecture overview](../architecture/overview.md)): **OpenTelemetry** (instrumentation) →
**Prometheus** (metrics) + **Tempo** (traces) + **Loki** (logs), unified in **Grafana**. All three
signals correlate on `trace_id`.

## The three signals and how they correlate

- **Metrics (Prometheus):** numeric time-series — rates, gauges, histograms. "How much / how fast /
  how many."
- **Traces (Tempo):** the path of one operation across components, including **across in-process
  event boundaries** (a webhook → SyncJob → ActivityIngested → Catalog normalization is one trace).
- **Logs (Loki):** structured events, each carrying `trace_id` so a log line jumps to its trace.

The decisive design point: because events are **in-process** (Spring Modulith, no broker), a single
`trace_id` propagates through the whole event chain — the OTel context rides with the
`@ApplicationModuleListener` invocation. So "what happened to this activity from webhook to fitness
chart" is **one trace**, not a correlation puzzle across services. This is a real advantage of the
modular-monolith choice ([ADR 0008](../adr/0008-event-transport.md)) and the reason Phase 7's exit
criterion ("a failing integration detectable within one minute") is achievable from one dashboard
([roadmap](../business/roadmap.md)).

## Key metrics by BC

Consolidated from each BC's own observability section.

### Identity & Access
- `token_refresh_total`, `token_refresh_failures_total`, `token_refresh_latency` (histogram)
- `connected_sources{status}` gauge (ACTIVE / REVOKED / ERROR counts)
- `tokens_expiring_soon` gauge
- JWT issued / validation-failure counts (by reason: expired / bad-sig / blacklisted)

### Activity Ingestion (the operationally hottest BC)
- `strava_ratelimit_short_used` / `_daily_used` gauges (from `X-RateLimit-Usage` — authoritative)
- `strava_ratelimit_429_total`, `strava_ratelimit_deferred_total{source}`
- `sync_jobs{status}` gauge (PENDING / IN_PROGRESS / FAILED / DEAD)
- `webhook_received_total`, `webhook_processing_lag` (received → processed)
- `ingestion_lag` (activity event_time → ingested)

### Workout Catalog / Performance Analytics
- normalization rate + failures; entity-graph fetch latency (Catalog)
- stream materialization batch size / duration; recompute-from-date span (Analytics)
- continuous-aggregate refresh lag; hypertable chunk count / compression ratio (Analytics)

### Training Planning
- match rate (auto vs manual); compliance-score distribution
- scheduler sweep durations (due / missed)

### Notifications
- delivery attempts by `{channel, status}`; retry counts
- SSE active connections gauge; emitter eviction rate
- `NotificationFailed` rate (terminal failures)

### Cross-cutting (Spring Modulith)
- `event_publication` incomplete-rows gauge — **the** signal for event-processing lag (the
  in-process analogue of consumer lag, [idempotency-and-outbox.md](../technical-notes/idempotency-and-outbox.md))
- DB connection-pool utilization; per-BC schema query latency

## Tracing

- **One trace per external trigger** — a webhook delivery, an API request, a scheduled sweep —
  propagated through the entire in-process event chain via OTel context.
- **Spans worth naming explicitly:** the Strava `GET /activities/{id}` (the rate-limited network
  hop), the `/oauth/token` refresh call, the hypertable bulk insert, each
  `@ApplicationModuleListener` boundary.
- **Trace ↔ webhook correlation:** the token-refresh span is correlated with its triggering webhook
  via `trace_id` ([token-management.md](../technical-notes/token-management.md)), so a refresh
  failure is traceable to the activity fetch that needed it.

## Logging

- **Structured (JSON) logs**, every line carrying `trace_id` and `user_id` (a UUID — safe to log).
- **Never log secrets:** decrypted tokens, signing keys, raw JWT values never appear in logs, spans,
  or error messages ([encryption-at-rest.md](../technical-notes/encryption-at-rest.md),
  [jwt-and-keys.md](../technical-notes/jwt-and-keys.md)). Token *access* is logged as a fact ("token
  used for user X, op Y"), never the value.
- **Log levels:** business-significant transitions (source REVOKED, job DEAD, notification FAILED) at
  INFO/WARN; the firehose (every webhook receipt) at DEBUG, sampled in production.

## Dashboards (Grafana, per BC + system)

- **System overview:** the single pane for Phase 7's "whole-system health" — Strava rate-limit
  headroom, ingestion lag, event-processing backlog, error rates, ConnectedSource health.
- **Per-BC dashboards:** each BC's metrics above, with its traces linked.
- **The Strava-budget panel** is the most operationally important single widget: short/daily usage
  vs limit, 429 rate, deferred-by-source — because the rate-limit budget is the system's real
  bottleneck ([strava-rate-limits.md](../technical-notes/strava-rate-limits.md)).

## Alert rules

Each alert maps to a runbook ([runbooks/](runbooks/)):

| Alert | Condition | Runbook |
|---|---|---|
| Strava rate-limit exceeded | sustained 429s, or short-window usage pinned at ceiling with growing PENDING backlog | [strava-rate-limit-exceeded](runbooks/strava-rate-limit-exceeded.md) |
| Ingestion lag | `ingestion_lag` above threshold for N minutes | [ingestion-lag-alert](runbooks/ingestion-lag-alert.md) |
| Token-refresh failures | spike in `token_refresh_failures_total` across many users | [token-refresh-failures](runbooks/token-refresh-failures.md) |
| Event-processing lag | `event_publication` incomplete rows growing | [event-processing-lag](runbooks/event-processing-lag.md) |
| DB connection saturation | pool utilization near max | [database-connection-saturation](runbooks/database-connection-saturation.md) |
| Deauthorization storm | spike in REVOKED transitions | [deauthorization-storm](runbooks/deauthorization-storm.md) |

Alert philosophy: alert on **symptoms users feel or that threaten the Strava integration** (lag,
rate-limit exhaustion, refresh failure), not on every internal blip. The Strava dependency is the
single point of failure ([architecture overview](../architecture/overview.md)), so its health
signals are the highest-priority alerts.

## What is deliberately NOT done (MVP)

- **No distributed tracing infra beyond OTel/Tempo** — one process, so no cross-service trace
  stitching needed (the in-process trace already spans the event chain).
- **No APM vendor** — the OSS stack (Prometheus/Tempo/Loki/Grafana) is the choice; a SaaS APM is not
  used.
- **No log-based metrics** where a real metric is cheaper — counts come from Prometheus counters, not
  Loki queries, except for ad-hoc investigation.

## Related

- [architecture overview](../architecture/overview.md) — the stack and the single-dependency constraint
- [roadmap Phase 7](../business/roadmap.md) — observability hardening exit criteria
- [strava-rate-limits.md](../technical-notes/strava-rate-limits.md) — the budget signals
- [idempotency-and-outbox.md](../technical-notes/idempotency-and-outbox.md) — the event_publication lag signal
- [security.md](security.md), [deployment.md](deployment.md) — the other operations docs
