# Architecture Decision Records

This directory contains all significant architectural decisions for Aperitivo, captured as Architecture Decision Records (ADRs).

## What is an ADR?

An ADR is a short document that captures one architectural decision: the context that prompted it, what we decided, what alternatives we rejected, and what consequences (positive and negative) we accept by making this choice.

Format adapted from [Michael Nygard's original ADR template](https://github.com/joelparkerhenderson/architecture-decision-record). Each ADR has a header (status, date, related ADRs) followed by **Context**, **Decision**, **Alternatives considered**, **Consequences** and **References**.

## Conventions

- **ADRs describe the current model.** When a later ADR changes part of an earlier, still-valid decision, the earlier ADR is revised in place to match. Its header gains a `Revised:` date and a link to the ADR that drove the change. Earlier wording lives in git history. A reader should never have to mentally apply a chain of amendments.
- **Superseded ADRs are archived.** When a decision is replaced entirely, the new ADR records what it replaces, and the old file moves to [`docs/archived/adr/`](../archived/adr/) via `git mv`, keeping its number. Numbers are never reused.
- **Numbering.** Strictly sequential, 4-digit, zero-padded (`0001`, `0002`, ...). Gaps are numbers that were never used or whose ADR is archived.
- **Status:** `Proposed` → `Accepted`. A replaced ADR is archived rather than kept here as `Superseded`.
- **One decision per ADR.** If a "decision" turns out to bundle multiple independent choices, split it.

## Index

| # | Title | Status |
|---|---|---|
| [0003](0003-token-storage-strategy.md) | Strava tokens stored in backend (Postgres, AES-GCM) | Accepted |
| [0004](0004-user-model-and-connected-sources.md) | Internal user UUID + ConnectedSource entity | Accepted |
| [0005](0005-bounded-contexts.md) | Bounded Context decomposition for MVP | Accepted |
| [0007](0007-sse-not-websocket.md) | Server-Sent Events instead of WebSocket | Accepted |
| [0008](0008-event-transport.md) | Event transport — Spring Modulith local events, no Kafka in MVP | Accepted |
| [0009](0009-identity-spring-security.md) | Identity in-app with Spring Security and our own JWT issuer | Accepted |
| [0011](0011-identity-provider-signup.md) | Identity-provider signup (Google); data sources connected on demand | Accepted |
| [0012](0012-spa-mobile-stateless-api.md) | SPA and mobile clients; the backend is a stateless API with ID-token login | Accepted |
| [0013](0013-access-and-refresh-tokens.md) | Short-lived access tokens with rotating refresh tokens | Accepted |

### Archived

| # | Title | Replaced by |
|---|---|---|
| [0002](../archived/adr/0002-identity-model-superseded.md) | "Sign in with Strava" via Keycloak custom Authenticator SPI | [0009](0009-identity-spring-security.md) |
| [0010](../archived/adr/0010-single-canonical-login-provider-superseded.md) | Single canonical login provider; additional sources linked by an authenticated user | [0011](0011-identity-provider-signup.md) |

Scope boundaries without an ADR of their own (no realtime live-streaming from Strava, no social features) are recorded as non-goals in [architecture/overview.md](../architecture/overview.md).

## Reading order

For someone new to the project, the decisions build on each other roughly in this order:

1. **0005** — how the system is split into Bounded Contexts.
2. **0008** — how those contexts talk to each other (in-process events).
3. **0009** — how users authenticate (Spring Security → our JWT).
4. **0004** — how users and their external connections are modeled.
5. **0011** — who may create an account (an identity provider), and when data sources attach.
6. **0012** — who the clients are (SPA, mobile) and how they log in and connect sources against a stateless API.
7. **0013** — the lifetime of our tokens: 15-minute access, rotating refresh.
8. **0003** — where and how Strava tokens are stored.
9. **0007** — server push (SSE, not WebSocket); scope boundaries are in the [overview](../architecture/overview.md) non-goals.
