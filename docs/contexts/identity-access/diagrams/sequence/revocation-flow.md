# Sequence: Revocation Flow

How a Strava connection ends — both triggers (the deauthorization webhook and a `401` on a data
call) converging on the same `ConnectedSourceService.markRevoked` transition and the
`IntegrationRevoked` fan-out to Ingestion and Notifications.

Contracts: [IAM events](../../events.md) (`IntegrationRevokedEvent`, `RevocationReason`),
[IAM domain-model](../../domain-model.md). The token-refresh `401` branch that feeds one of these
triggers is in [token-refresh.md](token-refresh.md).

## What this diagram shows

1. **Two independent triggers, one transition** — a deauth webhook and a `401` both end at
   `markRevoked`, with different `RevocationReason`s.
2. **The channel asymmetry** — deauth arrives on Ingestion's webhook channel but the *decision* is
   IAM's; the `401` is detected by Ingestion via `TokenManager` and routed to IAM. The revocation
   decision always belongs to IAM.
3. **The state-transition guard** — `IntegrationRevoked` fires only on the actual `ACTIVE → REVOKED`
   transition, never on an already-revoked source.
4. **The fan-out** — Ingestion cancels jobs; Notifications tells the user to reconnect.

## Diagram

```mermaid
sequenceDiagram
    autonumber
    participant ST as Strava
    participant WC as Ingestion<br/>WebhookController
    participant TM as Ingestion path<br/>via TokenManager
    participant CSS as IAM<br/>ConnectedSourceService
    participant CSR as connected_sources
    participant EP as event_publication
    participant ING as Ingestion<br/>(consumer)
    participant NOT as Notifications

    Note over ST,CSS: TRIGGER 1 — user deauthorizes on Strava (webhook channel)
    ST->>WC: POST webhook {object_type=athlete, updates.authorized=false}
    Note over WC: durable inbox receipt (200 fast), then async process
    WC->>CSS: markRevoked(userId, DEAUTHORIZED)

    Note over ST,CSS: TRIGGER 2 — a Strava data call returns 401 (token rejected)
    ST-->>TM: 401 invalid_token on GET /activities/{id} (or refresh)
    TM->>CSS: markRevoked(userId, TOKEN_REJECTED)

    Note over CSS,EP: ONE transition — the state-transition guard
    rect rgb(238, 248, 238)
        CSS->>CSR: load ConnectedSource(userId, STRAVA)
        alt status == ACTIVE (real transition)
            CSS->>CSR: status = REVOKED, set reason
            CSS->>EP: publish IntegrationRevokedEvent{userId, connectedSourceId, reason, occurredAt}
        else already REVOKED
            Note over CSS: no-op — guard prevents a duplicate event<br/>(invariant 5: event only on the actual transition)
        end
    end

    Note over EP,NOT: FAN-OUT — AFTER_COMMIT, at-least-once
    EP-->>ING: IntegrationRevokedEvent
    Note over ING: cancel pending SyncJobs for this user;<br/>stop issuing Strava calls. Idempotent — no dedup key needed.
    EP-->>NOT: IntegrationRevokedEvent
    Note over NOT: notify "reconnect your Strava".<br/>Real side effect (email) → dedup on connectedSourceId.
```

## The two triggers, side by side

| Trigger | Detected by | `RevocationReason` | Path to `markRevoked` |
|---|---|---|---|
| Strava deauthorization webhook | Ingestion `WebhookController` | `DEAUTHORIZED` | webhook channel → IAM `ConnectedSourceService` |
| `401` on a Strava data call / refresh | Ingestion via `TokenManager` | `TOKEN_REJECTED` | `TokenManager.markRevoked` → IAM `ConnectedSourceService` |
| In-app "disconnect" (post-MVP) | IAM API | `USER_REQUESTED` | IAM endpoint → `ConnectedSourceService` |

The third row (`USER_REQUESTED`) is defined but reserved for the post-MVP in-app disconnect flow; the
two MVP triggers are the first two ([IAM events](../../events.md)).

## Notes keyed to the locked decisions

- **The decision is always IAM's.** Even though deauth *arrives* on Ingestion's webhook endpoint and
  the `401` is *observed* by Ingestion, the revocation **decision and state transition** belong to
  IAM's `ConnectedSourceService`. Ingestion receives and routes; IAM decides. This is the channel
  asymmetry the IAM domain-model is explicit about.
- **The guard makes the event exactly-once-per-transition.** `IntegrationRevoked` fires only on a
  genuine `ACTIVE → REVOKED` change. A second trigger for an already-revoked source (e.g. a `401`
  after the deauth webhook already revoked) is a no-op — no duplicate event (invariant 5).
- **Consumers react idempotently.** Ingestion's cancel-jobs is naturally idempotent (cancelling
  already-cancelled jobs is a no-op) → no dedup key. Notifications sends a real email → dedups on
  `connectedSourceId` ([IAM events](../../events.md)).
- **`reason` is load-bearing.** It is in the payload because consumers act on it — Notifications
  phrases the message differently for a deliberate disconnect vs a silently rejected token, and the
  `deauthorization-storm` runbook keys on the reason distribution to tell a genuine mass-deauth from a
  credential failure masquerading as `401`s ([deauthorization-storm runbook](../../../../operations/runbooks/deauthorization-storm.md)).
- **Reconnect is clean.** The partial unique index allows a fresh `ACTIVE` source after a `REVOKED`
  one, so the user simply re-logs-in and a new source supersedes the dead one
  ([IAM domain-model](../../domain-model.md), invariant 3).
```
