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
| [0002](0002-identity-model.md) | "Sign in with Strava" via Keycloak custom Authenticator SPI | Accepted |
| [0003](0003-token-storage-strategy.md) | Strava tokens stored in backend, not Keycloak federation | Accepted |
| [0004](0004-user-model-and-connected-sources.md) | Internal user UUID + ConnectedSource entity | Accepted |
| [0005](0005-bounded-contexts.md) | Bounded Context decomposition for MVP | Accepted |
| [0006](0006-no-social-challenges.md) | No social features or public challenges | Accepted |
| [0007](0007-sse-not-websocket.md) | Server-Sent Events instead of WebSocket | Accepted |
