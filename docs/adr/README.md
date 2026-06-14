# Architecture Decision Records

This directory contains all significant architectural decisions for Aperitivo, captured as Architecture Decision Records (ADRs).

## What is an ADR?

An ADR is a short document that captures one architectural decision: the context that prompted it, what we decided, what alternatives we rejected, and what consequences (positive and negative) we accept by making this choice.

Format adapted from [Michael Nygard's original ADR template](https://github.com/joelparkerhenderson/architecture-decision-record). See [template.md](template.md).

## Conventions

- **Append-only.** ADRs are never deleted. If a decision is reversed, a new ADR is added that **supersedes** the old one (and the old one is marked as superseded with a forward link).
- **Numbering.** Strictly sequential, 4-digit, zero-padded (`0001`, `0002`, ...).
- **Status:** `Proposed` → `Accepted` → `Superseded by NNNN` (or `Deprecated` if simply abandoned).
- **One decision per ADR.** If a "decision" turns out to bundle multiple independent choices, split it.

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-no-strava-live-streaming.md) | No realtime live-streaming from Strava | Accepted |
| [0002](0002-identity-model.md) | "Sign in with Strava" via Keycloak custom Authenticator SPI | Superseded by [0009](0009-identity-spring-security.md) |
| [0003](0003-token-storage-strategy.md) | Strava tokens stored in backend (Postgres, AES-GCM) | Accepted |
| [0004](0004-user-model-and-connected-sources.md) | Internal user UUID + ConnectedSource entity | Accepted |
| [0005](0005-bounded-contexts.md) | Bounded Context decomposition for MVP | Accepted |
| [0006](0006-no-social-challenges.md) | No social features or public challenges | Accepted |
| [0007](0007-sse-not-websocket.md) | Server-Sent Events instead of WebSocket | Accepted |
| [0008](0008-event-transport.md) | Event transport — Spring Modulith local events, no Kafka in MVP | Accepted |
| [0009](0009-identity-spring-security.md) | Identity via Spring Security with own JWT issuer | Accepted |

## Reading order

For someone new to the project, the decisions build on each other roughly in this order:

1. **0005** — how the system is split into Bounded Contexts.
2. **0008** — how those contexts talk to each other (in-process events).
3. **0009** — how users authenticate (Strava OAuth → our JWT).
4. **0004** — how users and their external connections are modeled.
5. **0003** — where and how Strava tokens are stored.
6. **0001, 0006, 0007** — scope boundaries (no live-streaming, no social, SSE not WebSocket).
