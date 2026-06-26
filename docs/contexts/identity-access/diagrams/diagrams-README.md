# Diagrams

Visual architecture documentation. All diagrams are authored as **Mermaid** code embedded in Markdown — text-based, diff-friendly, renders natively on GitHub and in IDE previews.

## Organization

Diagrams are **co-located with their Bounded Context**. Each BC has its own `diagrams/` subfolder:

```
contexts/{bc}/
└── diagrams/
    └── sequence/   ← sequence diagrams for flows owned by this BC
```

Cross-BC or system-level views (C4, ERDs spanning multiple BCs) live here in `docs/diagrams/`:

```
diagrams/
├── c4/         ← C4 model views (System Context, Container, Component)
└── erd/        ← entity-relationship diagrams that span multiple BCs
```

## C4 Model

We use the [C4 model](https://c4model.com/) (Simon Brown). Three levels in this project:

- **Level 1 — System Context.** Aperitivo as a black box, surrounded by users and external systems (Strava API). [level-1-system-context.md](../../../diagrams/c4/level-1-system-context.md) — ✅ written
- **Level 2 — Containers.** Internal high-level deployment units: modular monolith app, Postgres+TimescaleDB, Redis, OTel collector, Grafana stack. [level-2-containers.md](../../../diagrams/c4/level-2-containers.md) — ✅ written
- **Level 3 — Components.** The six Bounded Contexts inside the app and their event/port relationships. [level-3-components.md](../../../diagrams/c4/level-3-components.md) — ✅ written

We do **not** maintain Level 4 (Code). Source code is the authoritative artifact at that level.

## Sequence diagrams (co-located)

Each BC owns its sequence diagrams under `contexts/{bc}/diagrams/sequence/`. Current diagrams:

| Diagram | BC | Status |
|---|---|---|
| [strava-oauth-login.md](../contexts/identity-access/diagrams/sequence/strava-oauth-login.md) | Identity & Access | ✅ written |
| [activity-ingestion-webhook.md](../../activity-ingestion/diagrams/sequence/activity-ingestion-webhook.md) | Activity Ingestion | ✅ written |
| [initial-backfill.md](../../activity-ingestion/diagrams/sequence/initial-backfill.md) | Activity Ingestion | ✅ written |
| [token-refresh.md](../contexts/identity-access/diagrams/sequence/token-refresh.md) | Identity & Access | ✅ written |
| [revocation-flow.md](../contexts/identity-access/diagrams/sequence/revocation-flow.md) | Identity & Access | ✅ written |

## Cross-BC ERDs

Per-BC entity-relationship diagrams are co-located with each context. Cross-BC ERDs live here:

- `erd/overview.md` — top-level entity map across all BCs — *to be written*

## Conventions

- Use `flowchart` for system topology, `sequenceDiagram` for flows, `erDiagram` for ERDs.
- Name participants in sequence diagrams consistently with the BC catalog and glossary.
- Include a one-paragraph caption above each diagram explaining what it shows and why.
- Keep diagrams focused — one concept per diagram. If a diagram tries to show two things, split it.
- No external diagramming tools (draw.io, Lucidchart). All diagrams are Mermaid in Markdown — diff-friendly and renders on GitHub.
