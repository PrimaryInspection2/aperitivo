# Operations

Runtime concerns for Aperitivo: observability, security, deployment, and incident response.

## Documents

- [Observability](observability.md) — OpenTelemetry, metrics, logs, traces, dashboards — *to be written*
- [Security](security.md) — threat model, secrets management, encryption-at-rest, audit logging — *to be written*
- [Deployment](deployment.md) — Docker Compose layout, configuration, environment variables — *to be written*
- [Runbooks](runbooks/) — incident response playbooks — *to be added*

## Anticipated runbooks

- `runbooks/strava-rate-limit-exceeded.md` — what to do when we hit the 15-min or daily quota
- `runbooks/ingestion-lag-alert.md` — when ingestion is falling behind
- `runbooks/token-refresh-failures.md` — pattern of refresh failures across many users
- `runbooks/event-processing-lag.md` — Spring Modulith event listeners falling behind (incomplete `event_publication` rows accumulating); the in-process analogue of consumer lag
- `runbooks/database-connection-saturation.md`
- `runbooks/deauthorization-storm.md` — many users revoking at once (e.g. after a Strava ToS change)
