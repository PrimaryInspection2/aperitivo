# Diagrams

Visual architecture documentation. All diagrams are authored as **Mermaid** code embedded in Markdown — text-based, diff-friendly, renders natively on GitHub and in IDE previews.

## Organization

```
diagrams/
├── c4/         ← C4 model views (System Context, Container, Component)
├── sequence/   ← sequence diagrams for important flows
└── erd/        ← entity-relationship diagrams per BC
```

## C4 Model

We use the [C4 model](https://c4model.com/) (Simon Brown). Three levels in this project:

- **Level 1 — System Context.** Aperitivo as a black box, surrounded by users and external systems (Strava API). [c4/level-1-system-context.md](c4/level-1-system-context.md) — *to be written*
- **Level 2 — Containers.** Internal high-level deployment units: modular monolith app, Postgres+TimescaleDB, Redis, OTel collector, Grafana stack. [c4/level-2-containers.md](c4/level-2-containers.md) — *to be written*
- **Level 3 — Components per BC.** One per Bounded Context, showing internal components and their relationships. [c4/level-3-components-{bc}.md](c4/) — *to be written per BC*

We do **not** maintain Level 4 (Code). Source code is the authoritative artifact at that level.

## Sequence diagrams

For complex, multi-actor flows where temporal ordering matters:

- `sequence/strava-oauth-login.md` — *to be written* — full flow from "Sign in with Strava" click to JWT issuance
- `sequence/activity-ingestion-webhook.md` — *to be written* — Strava webhook → SyncJob → Catalog → Analytics → Notifications
- `sequence/token-refresh.md` — *to be written* — lazy refresh with per-user lock
- `sequence/initial-backfill.md` — *to be written* — paginated history sync on first sign-up
- `sequence/revocation-flow.md` — *to be written* — 401 from Strava → IntegrationRevoked → cancel jobs → notify user

## ERDs

Per-BC entity-relationship diagrams co-located with each context's documentation, or in `erd/` if cross-BC:

- `erd/iam.md` — Identity & Access entities (User, ConnectedSource) — *to be written*
- `erd/catalog.md` — Workout Catalog entities (Workout, Split, Stream) — *to be written*
- `erd/analytics.md` — Performance Analytics hypertables — *to be written*
- `erd/planning.md` — Training Planning entities (Plan, ScheduledSession) — *to be written*

## Conventions

- Use `flowchart` for system topology, `sequenceDiagram` for flows, `erDiagram` for ERDs.
- Name participants in sequence diagrams consistently with the BC catalog and glossary.
- Include a one-paragraph caption above each diagram explaining what it shows and why.
- Keep diagrams focused — one concept per diagram. If a diagram tries to show two things, split it.
- No external diagramming tools (draw.io, Lucidchart). All diagrams are Mermaid in Markdown — diff-friendly and renders on GitHub.
