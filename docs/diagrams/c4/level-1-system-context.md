# C4 Level 1 — System Context

Aperitivo as a black box, surrounded by the people who use it and the external systems it depends
on. This is the highest-level view: *who* uses the system and *what* it talks to, with no internal
detail. C4 model: [c4model.com](https://c4model.com/). Conventions:
[diagrams-README](../../contexts/identity-access/diagrams/diagrams-README.md).

## What this shows

The system's place in the world: one type of user (the athlete) and two external systems — Google
(the identity provider, login only) and Strava (the data source: OAuth connect + REST, and
webhooks). Their arrows differ in meaning — Google authenticates, Strava supplies data
([ADR 0011](../../adr/0011-identity-provider-signup.md)). Everything inside the Aperitivo box is
deferred to [Level 2 — Containers](level-2-containers.md).

## Diagram

```mermaid
flowchart TB
    Athlete["👤 Athlete<br/><i>Endurance athlete (runner / cyclist / triathlete)</i><br/>Signs in with Google, connects Strava, views fitness & plans"]

    subgraph Aperitivo["Aperitivo — training analytics platform"]
        APP["<b>Aperitivo</b><br/>Ingests Strava activities, computes fitness/form,<br/>matches plans, notifies the athlete"]
    end

    Google["🔑 Google<br/><i>External system — identity provider</i><br/>OIDC login / signup (supplies no data)"]
    Strava["🏃 Strava<br/><i>External system — data source</i><br/>Activity data (OAuth connect) + webhooks"]

    Athlete -->|"Signs in, views dashboards,<br/>creates plans (HTTPS)"| APP
    APP -->|"OIDC login / signup<br/>(HTTPS)"| Google
    APP -->|"Connect (OAuth), fetch activities,<br/>refresh tokens (HTTPS / REST)"| Strava
    Strava -->|"Activity & deauth webhooks<br/>(HTTPS POST)"| APP
    APP -->|"Notifications<br/>(email, in-app SSE)"| Athlete
```

## The relationships

| From | To | Nature |
|---|---|---|
| Athlete → Aperitivo | HTTPS (browser/app) | sign in via Google, connect Strava, view fitness/form/PRs, author training plans |
| Aperitivo → Google | OIDC (OAuth 2.0) | login / signup — the identity provider; supplies no activity data |
| Aperitivo → Strava | OAuth 2.0 + REST | connect the data source, fetch activity detail & streams, refresh tokens — all **server-to-server** (confidential client) |
| Strava → Aperitivo | Webhooks (HTTPS POST) | realtime activity create/update/delete + athlete deauthorization |
| Aperitivo → Athlete | Email + SSE | PR notifications, session reminders, reconnect prompts |

## Key facts at this level

- **Two external systems, distinct roles.** Strava is the *single point of dependency for data* — if
  Strava revokes API access or changes terms, the product is non-functional as a data platform.
  Google is the *identity provider* — in the critical path of every login, but supplying no data.
  Both are accepted dependencies for a portfolio project
  ([architecture overview](../../architecture/overview.md),
  [ADR 0011](../../adr/0011-identity-provider-signup.md)).
- **Two Strava channels, deliberately distinct.** The OAuth/REST channel (we initiate) and the
  webhook channel (Strava initiates) are owned by different internal contexts — a distinction that
  becomes visible at Level 2/3 ([IAM domain-model](../../contexts/identity-access/domain-model.md)).
- **One user type in MVP.** The athlete. No coach/admin/multi-tenant roles yet (post-MVP).
- **Aperitivo issues its own identity.** Although login is via Google OIDC, Aperitivo mints its own
  JWT — Google authenticates the user, but there is no external identity *server* issuing our tokens
  ([ADR 0009](../../adr/0009-identity-spring-security.md),
  [ADR 0011](../../adr/0011-identity-provider-signup.md)). At this level that's invisible; it
  surfaces at Level 2.

## Not shown here (deferred to lower levels)

- Internal structure (the modular monolith, its BCs) → [Level 2](level-2-containers.md).
- Backing services (Postgres, Redis, observability) → [Level 2](level-2-containers.md).
- The six Bounded Contexts and their interactions → [Level 3](level-3-components.md).
- Future data providers (Garmin, Wahoo, …) — not in MVP; would appear as additional external systems
  alongside Strava ([ADR 0004](../../adr/0004-user-model-and-connected-sources.md)). Future identity
  providers (Apple, …) would likewise appear alongside Google
  ([ADR 0011](../../adr/0011-identity-provider-signup.md)).

## Related

- [Level 2 — Containers](level-2-containers.md)
- [Level 3 — Components](level-3-components.md)
- [architecture overview](../../architecture/overview.md)
```
