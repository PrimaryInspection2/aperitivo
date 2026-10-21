# Identity & Access — Events

The events the IAM Bounded Context **publishes**, their payload contracts, when they are emitted, the guarantees that hold, and how each maps onto the externalization envelope. IAM consumes **no** internal events.

Conventions in force: [events/conventions.md](../../events/conventions.md), catalog: [events/catalog.md](../../events/catalog.md), transport: [ADR 0008](../../adr/0008-event-transport.md). Naming: [conventions/naming.md](../../conventions/naming.md). Source of the entities and emit points: [domain-model.md](domain-model.md).

## What IAM publishes

| Event | Logical name | Emitted by | When |
|---|---|---|---|
| `UserRegisteredEvent` | `user-registered.v1` | `LoginService` | a new `UserEntity` is created (first login only) |
| `IntegrationConnectedEvent` | `integration-connected.v1` | `ConnectedSourceService` | a `ConnectedSourceEntity` transitions to `ACTIVE` (connect or reconnect) |
| `IntegrationRevokedEvent` | `integration-revoked.v1` | `ConnectedSourceService` | a source transitions `ACTIVE → REVOKED` |

### The two lifecycles are not simultaneous

This is the most important property of this contract, and it is invisible in the payloads.

Signup happens through an identity provider, and sources are connected afterwards, on demand ([ADR 0011](../../adr/0011-identity-provider-signup.md)). "A user exists" and "that user has a data source" are separate facts, and the two events are separated by an arbitrary interval — seconds, days, or **forever**, because connecting is optional.

Consumers must therefore treat them as unrelated:

- **`UserRegisteredEvent` does not imply a data source exists.** Anything that reads sources, tokens, or workouts on registration will find nothing. There is no ordering or causality between the two events.
- **`IntegrationConnectedEvent` is the sole trigger for ingestion backfill.** It is the first moment at which there is anything to fetch.

## What IAM consumes

**Nothing from inside the system.** IAM's only inputs are external, and there are three of them — the Google ID token presented to `POST /api/auth/google` and the Strava authorization code presented to `POST /api/connect/strava` (both handled here) and the Strava deauthorization webhook (received by Activity Ingestion's `WebhookController` and routed back to `ConnectedSourceService.markRevoked`). See the "three channels" section of [domain-model.md](domain-model.md). None is a domain event; all three are external HTTP inputs.

A BC consuming its own events is a smell (see conventions). IAM does not.


---

## Transport recap

All three events are Java `record`s published via `ApplicationEventPublisher` from inside the emitting service's `@Transactional` method. Spring Modulith writes the publication to the `event_publication` table **in the same transaction** as the business change (Outbox), and delivers it **after commit** to `@ApplicationModuleListener` consumers in other BCs. If the transaction rolls back, no event exists; if it commits, the event is durably recorded and retried until each listener completes.

Delivery is therefore **at-least-once**. Redelivery happens only for publications that did not complete (a listener crashed, or the app restarted with the publication still open); Modulith then re-delivers the same event. Consumer idempotency for that window is discussed under [Guarantees](#guarantees-and-emit-conditions).

Events are plain records — they do **not** extend `org.springframework.context.ApplicationEvent` (see naming conventions).

---

## Event identity — what we carry now, and why

We do **not** put a synthetic `eventId` field in the records. In the MVP, event identity is owned by Spring Modulith: each publication is a row in `event_publication` with its own id, stable across retries for that `(event, listener)` pair. Modulith's completion tracking is the primary guard against reprocessing; where a consumer needs an explicit dedup key it uses a **natural business key already in the payload** (see below) rather than a synthetic id.

This is a deliberate scoping decision, not an oversight. A synthetic, payload-carried `eventId` buys exactly one thing: a dedup key that stays byte-identical across the externalization boundary, so consumers never touch their dedup logic the day events go to a broker. Until externalization is a real plan rather than a hypothetical, that benefit is unrealized and the field is dead weight. If events are ever externalized, identity is added **then** — either by relaying Modulith's publication id into a message header, or by adding an `eventId` field, which is a backward-compatible change (new optional field, same `v1`). The records below therefore match the illustrative shape in [naming.md](../../conventions/naming.md) exactly.

`occurredAt` **is** carried — it is the domain time the fact happened (assigned by the emitting service at event construction, inside the transaction), which is distinct from the publication/commit time Modulith records. Because the record is serialized once into `event_publication`, a redelivery carries the same `occurredAt`.

---

## The externalization envelope

The [event catalog](../../events/catalog.md) defines a common wire envelope (`event_id`, `event_type`, `event_version`, `occurred_at`, `producer`, `trace_id`, `user_id`, `data`). That envelope is the **externalization contract** — the shape an event takes *if* it is ever relayed to a broker. In-process today there is no separate envelope object: the published record carries the domain-meaningful fields, and the rest of the envelope is derived at the externalization boundary.

The mapping (relevant only when/if externalized):

| Envelope field | Source | Notes |
|---|---|---|
| `event_id` | Spring Modulith publication id | added at externalization; **not** a record field today |
| `event_type` | record class simple name minus the `Event` suffix | `IntegrationConnectedEvent` → `"IntegrationConnected"` |
| `event_version` | the `.vN` suffix of the logical name | `v1`; bumped per the versioning rules in conventions |
| `occurred_at` | `record.occurredAt` | domain time the fact happened |
| `producer` | constant `"identity-access"` | the publishing module |
| `trace_id` | current OTel span context | attached at externalization, not a domain field |
| `user_id` | `record.userId` | always present; partition key if externalized |
| `data` | the remaining record fields | event-specific payload |

The JSON "externalized form" shown per event below is this wire shape — it includes `event_id` because that is filled in at the externalization boundary, even though the in-process record does not carry it.

---

## Event contracts

### `UserRegisteredEvent`

A new internal identity was created. Emitted **only** on the first-ever login for a given identity-provider subject (`(GOOGLE, sub)`); a returning login does not emit it.

```java
public record UserRegisteredEvent(
        UUID    userId,       // the newly created user; also the partition key
        Instant occurredAt    // when the user was provisioned
) {}
```

**Consumers:** none in MVP. Reserved for a future analytics/onboarding consumer.

**What it does *not* mean.** It does not mean the user has a data source, tokens, or any workouts — see "The two lifecycles are not simultaneous" above. A newly registered account is completely empty. A future onboarding consumer should treat this event as "start the welcome/nudge-to-connect track", never as "their history is ready".

**Payload rationale:** deliberately minimal. There is no current consumer, so the contract carries only identity and time. Fields a future consumer might want (e.g. `displayName` for a welcome flow) are **backward-compatible additions** (optional fields, same `v1`) when that consumer appears — not stuffed in speculatively now. The identity provider is likewise absent: no consumer's behavior depends on *which* provider the user signed up with, and adding `provider` later is additive.

**Dedup key (if a consumer needs one):** `userId` — a user is registered exactly once, so the user id itself distinguishes the fact.


**Externalized form:**

```json
{
  "event_id": "8f1c…",
  "event_type": "UserRegistered",
  "event_version": "v1",
  "occurred_at": "2026-06-21T18:30:00Z",
  "producer": "identity-access",
  "trace_id": "…",
  "user_id": "3a9e…",
  "data": {}
}
```

(`data` is empty because all current fields are envelope-level.)

---

### `IntegrationConnectedEvent`

A `ConnectedSource` for the user is now `ACTIVE` and usable. Emitted on every transition to `ACTIVE`: the **first** connect, and each **reconnect** after a prior revoke. Re-running the connect flow against an already-`ACTIVE` source refreshes its tokens but is not a transition, so it emits nothing.

Emitted from the **connect flow**, never from login, and never in the same transaction as `UserRegisteredEvent`.

```java
public record IntegrationConnectedEvent(
        UUID     userId,
        UUID     connectedSourceId,  // the now-ACTIVE source
        Provider provider,           // STRAVA in MVP
        Instant  occurredAt
) {}
```

**Consumers:** Activity Ingestion — triggers the activity sync for this source. This is the **only** trigger for the initial backfill; nothing starts syncing at registration, because at registration there is nothing to sync.

**First connect vs reconnect.** The event is the **same** in both cases by design; IAM does not encode the distinction. Activity Ingestion decides full backfill vs incremental catch-up from the presence/absence of a `SyncCursor` for `(userId, provider)` — that is Ingestion's concern, not part of this contract. (If Ingestion later needs the hint in the event itself, a `firstConnection` boolean is a backward-compatible addition.)

**Payload rationale:** `userId` + `provider` are all Ingestion strictly needs — it obtains the access token via `TokenManager.getValidAccessToken(userId)` and never receives tokens in the event. `providerUserId` is intentionally **absent**: Strava infers the athlete from the access token, so Ingestion does not need it for the API call, and it is available from `ConnectedSourceEntity` if ever required. `connectedSourceId` is carried for correlation and logging.

> **`connectedSourceId` is not a per-occurrence dedup key.** Reconnecting the same Strava athlete **reactivates the existing row in place** rather than inserting a new one — forced by the non-partial `UNIQUE (provider, provider_user_id)` constraint (see the reconnect table in [database.md](database.md)). So the same `connectedSourceId` recurs across a revoke/reconnect cycle, and a consumer that stored it as a "seen" key would silently drop every reconnect — the exact case where restarting ingestion matters most.
>
> Consumers must instead be **idempotent on the intent**: "ensure a sync is scheduled for `(userId, provider)`", which is naturally idempotent and needs no dedup storage (pattern 1 in [idempotency-and-outbox.md](../../technical-notes/idempotency-and-outbox.md)). Where a consumer genuinely needs to distinguish occurrences, the key is `(connectedSourceId, occurredAt)`.


**Externalized form:**

```json
{
  "event_id": "…",
  "event_type": "IntegrationConnected",
  "event_version": "v1",
  "occurred_at": "2026-06-21T18:30:00Z",
  "producer": "identity-access",
  "trace_id": "…",
  "user_id": "3a9e…",
  "data": {
    "connectedSourceId": "c12d…",
    "provider": "STRAVA"
  }
}
```

---

### `IntegrationRevokedEvent`

The user's connection to the provider is no longer usable. Emitted on every `ACTIVE → REVOKED` transition (invariant 6 in the domain model). Not re-emitted if the source is already `REVOKED` — the state-transition guard in `ConnectedSourceService` ensures the event fires only on the actual transition.

```java
public record IntegrationRevokedEvent(
        UUID             userId,
        UUID             connectedSourceId,
        Provider         provider,
        RevocationReason reason,        // why the connection was revoked
        Instant          occurredAt
) {}
```

**Consumers:**
- **Activity Ingestion** — cancels pending `SyncJob`s for this user and stops issuing Strava calls. Naturally idempotent (cancelling already-cancelled jobs is a no-op), so it needs no dedup key.
- **Notifications** — tells the user the connection broke and to reconnect. This sends a real side effect (email), so it dedups on `(connectedSourceId, occurredAt)`. **Not `connectedSourceId` alone**: because reconnect reactivates the row in place, one source can be revoked, reconnected and revoked again, and a key of `connectedSourceId` alone would suppress every notification after the first.

**Payload rationale:** `reason` is part of the contract because consumers act on it. Notifications can phrase the message differently for a user-initiated disconnect vs a silently rejected token; diagnostics and the `deauthorization-storm` runbook care about the distribution of reasons.


**Externalized form:**

```json
{
  "event_id": "…",
  "event_type": "IntegrationRevoked",
  "event_version": "v1",
  "occurred_at": "2026-06-21T18:30:00Z",
  "producer": "identity-access",
  "trace_id": "…",
  "user_id": "3a9e…",
  "data": {
    "connectedSourceId": "c12d…",
    "provider": "STRAVA",
    "reason": "DEAUTHORIZED"
  }
}
```

---

## `RevocationReason`

A domain value type (no suffix, per naming conventions). Set by `ConnectedSourceService.markRevoked(...)` and carried on `IntegrationRevokedEvent`.

```java
public enum RevocationReason {
    DEAUTHORIZED,    // Strava deauthorization webhook (updates.authorized=false)
    TOKEN_REJECTED,  // a Strava API call returned 401 / invalid_token
    USER_REQUESTED   // user disconnected the source from within Aperitivo
}
```

Detection point → reason:

| Trigger | Detected by | Reason | Path to `markRevoked` |
|---|---|---|---|
| Strava deauth webhook | Ingestion `WebhookController` | `DEAUTHORIZED` | webhook channel → `ConnectedSourceService.markRevoked` |
| 401 on a Strava data call | Ingestion (via `TokenManager`) | `TOKEN_REJECTED` | `TokenManager.markRevoked` → `ConnectedSourceService.markRevoked` |
| In-app "disconnect" action | IAM API | `USER_REQUESTED` | `DELETE /api/me/sources/{provider}` → `ConnectedSourceService.markRevoked` |

All three reasons are exercised in MVP. `USER_REQUESTED` is safe because disconnecting a source never touches the account's way to log in — the user's sign-in method lives in `user_identities`, untouched by source management. See [api.md](api.md).


---

## Guarantees and emit conditions

1. **`UserRegisteredEvent` fires exactly once per user**, at creation, from the login transaction. A returning login resolves the existing user and emits nothing.
2. **`IntegrationConnectedEvent` fires on every transition to `ACTIVE`** — first connect and each reconnect after revoke — from the connect transaction, which is **not** the login transaction.
3. **`IntegrationRevokedEvent` fires on every `ACTIVE → REVOKED` transition**, and only then. Re-revoking an already-revoked source is a no-op with no event.
4. **Transactional publication.** All three are written to `event_publication` in the same transaction as the state change they describe (Modulith Outbox). No dual write.
5. **At-least-once delivery; idempotent consumers.** Modulith re-delivers only incomplete publications, and its completion tracking is the primary guard. For the crash-after-side-effect window, consumers that produce external effects add their own idempotency: Ingestion via naturally idempotent state transitions (ensure a sync is scheduled, cancel jobs, mark status), Notifications via an Inbox keyed on the natural business key in the payload (`(connectedSourceId, occurredAt)` / `userId`). No synthetic event id is required for this in the MVP.
6. **No ordering or causality between registration and connection.** `UserRegisteredEvent` and `IntegrationConnectedEvent` come from **different transactions at different times**, separated by however long the user takes to connect a source — possibly forever. No consumer may assume one implies or precedes the other in any useful window. (Their consumers are distinct and independent, so nothing needs such a guarantee.)


---

## What is deliberately NOT an event here

Per the "what is NOT a domain event" rule in conventions:

- **Returning-user login.** No state change worth broadcasting — no `UserRegistered`, and no `IntegrationConnected` (login does not touch `connected_sources` at all).
- **Linking or unlinking a sign-in method.** `user_identities` changes are private to IAM: no other BC keys off *how* a user authenticates, only off `userId`, which never changes. Adding an Apple login to an existing account changes nothing downstream.
- **Token refresh** and **successful token use.** Internal `TokenManager` mechanics; observed via metrics/traces, not events (see [token-management.md](../../technical-notes/token-management.md)).
- **Access-token issuance, refresh-token rotation, and logout.** Synchronous results of `/api/auth/*` requests, returned to the caller — not facts broadcast to other BCs. A detected refresh-token reuse that revokes a family is likewise IAM-internal ([ADR 0013](../../adr/0013-access-and-refresh-tokens.md)).
- **`ERROR`-state transitions** on a `ConnectedSource`. `ERROR` is a transient retry state; it does not stop Ingestion or notify the user, so it carries no event. Only the terminal `REVOKED` transition does.

---

## Next documents in this BC

- [api.md](api.md) — REST endpoints (Google login, refresh, logout, Strava connect, current user, sources, identities)
- Sequence diagrams: [google-login.md](diagrams/sequence/google-login.md) (where `UserRegisteredEvent` fires), [strava-connect.md](diagrams/sequence/strava-connect.md) (where `IntegrationConnectedEvent` fires), [revocation-flow.md](diagrams/sequence/revocation-flow.md)
- [database.md](database.md) — already written
- [domain-model.md](domain-model.md) — already written

