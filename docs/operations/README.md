# Operations

Runtime concerns for Aperitivo: observability, security, deployment, and incident response.

## Documents

- [Observability](observability.md) — OpenTelemetry, metrics, traces, logs, dashboards, alerts
- [Security](security.md) — threat model, attack surface, secrets management, audit logging
- [Deployment](deployment.md) — Docker Compose topology, configuration, migrations, scaling notes
- [Runbooks](runbooks/) — incident response playbooks (below)

## Runbooks

- [strava-rate-limit-exceeded](runbooks/strava-rate-limit-exceeded.md) — hitting the 15-min or daily quota
- [ingestion-lag-alert](runbooks/ingestion-lag-alert.md) — ingestion falling behind
- [token-refresh-failures](runbooks/token-refresh-failures.md) — refresh failures across many users
- [event-processing-lag](runbooks/event-processing-lag.md) — Modulith listeners behind (incomplete `event_publication` rows); the in-process analogue of consumer lag
- [database-connection-saturation](runbooks/database-connection-saturation.md) — shared-Postgres pool exhaustion
- [deauthorization-storm](runbooks/deauthorization-storm.md) — many users revoking at once (and how to tell a real storm from our-side credential failure)
