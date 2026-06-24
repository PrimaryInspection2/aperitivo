# Activity Ingestion — API

The HTTP surface of the Activity Ingestion Bounded Context. Unlike IAM, Ingestion exposes almost no *user-facing* REST: its primary interface is the **Strava-facing webhook endpoint**, and its other two work sources (backfill, reconciliation) are not HTTP at all — they are an event listener and a scheduler. This document is explicit about which parts are external HTTP, which are internal triggers, and what is deliberately absent.

Webhook receipt/processing split and entity contracts: [domain-model.md](domain-model.md). Events: [events.md](events.md). Error shape (for the JSON parts): project-wide [API error conventions](../../conventions/api-errors.md).

## Endpoint surface

| Method & path | Owner | Auth | Purpose |
|---|---|---|---|
| `GET /api/webhooks/strava` | our controller | Strava subscription handshake | Webhook **validation** — echo `hub.challenge` when Strava (re)subscribes |
| `POST /api/webhooks/strava` | our controller | verify token + source | Webhook **receipt** — accept a delivery, persist it, answer `200` fast |

That is the **entire** external HTTP surface. There is **no** user-facing REST in Ingestion in the MVP — a user does not "trigger a sync" by hand. The two non-webhook work sources are internal:

| Trigger | Mechanism | Not HTTP because |
|---|---|---|
| Initial backfill | `@ApplicationModuleListener` on `IntegrationConnectedEvent` | it reacts to an IAM fact, in-process |
| Periodic reconciliation | `@Scheduled` sweep | it is time-driven, not request-driven |

---

## `GET /api/webhooks/strava` — subscription validation

When we create (or Strava re-validates) the webhook subscription, Strava issues a `GET` to our callback URL with a challenge:

```
GET /api/webhooks/strava?hub.mode=subscribe&hub.challenge=<random>&hub.verify_token=<ours>
```

We must:
1. Check `hub.verify_token` equals the secret token we configured for the subscription (rejects anyone else hitting the endpoint).
2. Echo the challenge back verbatim:

```json
{ "hub.challenge": "<random>" }
```

This is a **one-time-ish handshake** (on subscription creation and occasional re-validation), not part of the per-activity hot path. There is **one subscription per environment**, not per user — the subscription is an app-level resource, configured once. A failed handshake means the subscription is never established, so this endpoint must be correct before any webhook can arrive.

> No `ConnectedSource` lookup here — the handshake is about the *subscription*, which is app-global, not about any user. `hub.verify_token` is the only credential.

---

## `POST /api/webhooks/strava` — webhook receipt

The hot, external-facing path. Strava `POST`s here for every activity create/update/delete and for athlete deauthorization. The body carries **only identifiers**, never activity data:

```json
{
  "aspect_type": "create",
  "object_type": "activity",
  "object_id": 14207176594,
  "owner_id": 134815,
  "subscription_id": 120475,
  "event_time": 1718995800,
  "updates": {}
}
```

For deauthorization, the shape is `aspect_type: "update"`, `object_type: "athlete"`, `updates: { "authorized": "false" }`.

### The 2-second rule governs everything

Strava expects a `2xx` within **~2 seconds**, and treats slow/failed responses as delivery failures — chronic failure can deactivate the subscription. So the handler does the **minimum** before responding and defers all real work:

> Strava **retries** a delivery up to **3 attempts** total if it does not receive a `200`. This is a useful backstop, but **not** a substitute for our own durability: 3 attempts can all fall inside the same outage, and Strava gives up after them. Our `RECEIVED`-row-before-`200` guarantees receipt survives independently of Strava's retry budget; reconciliation is the final net under both. The three layers stack — Strava retry (seconds), durable inbox (survives our crash), reconciliation (survives all of the above).

```java
@PostMapping("/api/webhooks/strava")
public ResponseEntity<Void> receive(@RequestBody String rawBody,
                                    @RequestHeader HttpHeaders headers) {
    webhookService.receive(rawBody, headers);   // verify + ONE insert (status=RECEIVED)
    return ResponseEntity.ok().build();          // 200, immediately
    // processing happens after commit, asynchronously — NOT in this request
}
```

What happens **before** `200` (synchronous, cheap):
1. **Verify** the delivery is genuinely from our subscription (`subscription_id` matches the one we registered; reject otherwise). This is a cheap in-memory check, no DB, no crypto round-trip.
2. **Insert** one `WebhookEventEntity` row (`raw_body`, parsed routing columns, `status=RECEIVED`). One blind insert — no `ConnectedSource` resolution, no enum-mapping of Strava's `aspect_type`, no job creation.
3. Respond `200`.

What happens **after** `200` (asynchronous, off the Strava-facing path):
- `WebhookService.process` reads the `RECEIVED` row and routes it: activity → `SyncJobService.enqueue`; deauth → IAM `ConnectedSourceService.markRevoked`; irrelevant → `IGNORED`; unresolvable → `FAILED`. See [domain-model.md](domain-model.md).

### Why receipt persists *before* resolving anything

Resolving `owner_id` to a known `ConnectedSource` is exactly the kind of DB-touching work that must **not** sit between the `POST` and the `200`. If we resolved synchronously and our DB were under pressure (the `database-connection-saturation` scenario), the response could breach 2 seconds — and we'd lose deliveries for a reason that has nothing to do with Strava. Persisting first, resolving later, decouples Strava's view of our health from our internal load. Receipt is durable (committed before `200`), so a crash after the response loses nothing — the `RECEIVED` row is picked up on restart.

### Verification scope (MVP)

Strava's published webhook spec does **not** define a per-delivery signature: the documented security mechanism is the `verify_token`, and that token is sent only on the one-time validation `GET`, **not** on the per-event `POST`. So the guards available with certainty are weak, and our design must not lean on the webhook being authenticated:
- **`subscription_id` match** — the delivery names a subscription; we accept only our own. (Weak: `subscription_id` is a guessable integer, not a secret — treat it as a filter, not authentication.)
- **Endpoint obscurity + `verify_token`** established at subscription time (the token guards the handshake, not subsequent deliveries).
- **`owner_id` resolution** (during async processing, not at receipt) — an `owner_id` that maps to no known active source is marked `FAILED` and creates no job.

> **`X-Strava-Signature` — verify at implementation time.** Strava's official webhook *example* code reads an `X-Strava-Signature` header (HMAC-SHA256 over `timestamp.body` with a signing secret) — but this header is **not** in the main Webhook Events API spec, and community reports indicate it historically did not exist on deliveries. The state of this header should be **confirmed against a live subscription during Phase 2 implementation**. If Strava does send it, HMAC verification becomes the **preferred** receipt-time guard (cheap, in-memory, no DB) and should replace reliance on `subscription_id`. The design below does not assume it, so adding it is a strict hardening, not a contract change.

We do **not** treat the webhook body as trusted activity data — it carries only identifiers, and we re-fetch the actual activity from Strava with our own credentials. So even a forged webhook can at most cause a wasted `GET` for an `object_id` (rate-limit-bounded, and a non-existent id 404s harmlessly). The trust boundary is the **authenticated `GET` to Strava**, not the inbound webhook — which is why weak inbound verification is tolerable in the MVP even before the `X-Strava-Signature` question is settled.

### Responses

| Status | When |
|---|---|
| `200 OK` | delivery accepted and persisted (the normal case — including for deliveries we'll later `IGNORE` or `FAIL` during processing) |
| `400 Bad Request` | body is not parseable as a webhook envelope at all |
| `401 Unauthorized` | `subscription_id` / verify token does not match ours |

Note: we answer `200` even for a webhook we will ultimately ignore or fail to process — because *receipt* succeeded. Strava only cares that we received it. Processing outcomes (`IGNORED`/`FAILED`) are our internal state, not signalled back to Strava (there is no channel to, and no benefit). This keeps the contract with Strava simple: "did you get it?" — yes.

---

## Internal triggers (not HTTP)

These complete the three work sources but are not endpoints. Documented here so the BC's full input surface is in one place.

### Backfill — on `IntegrationConnectedEvent`

```java
@ApplicationModuleListener
void onIntegrationConnected(IntegrationConnectedEvent event) {
    // paginate GET /athlete/activities, enqueue SyncJob(source=BACKFILL) per activity
}
```

In-process, after IAM commits the new `ACTIVE` source. Full-history vs incremental decision is read from Ingestion's own `SyncCursor` (see [events.md](events.md#consumed-events)). Rate-limit-aware pagination is a mechanic of `BackfillService`, detailed in the backfill sequence diagram.

### Reconciliation — scheduled sweep

```java
@Scheduled(/* every N hours */)
void reconcileAll() {
    // for each ACTIVE source: GET /athlete/activities?after={cursor},
    // enqueue SyncJob(source=RECONCILIATION) per not-yet-seen activity, advance cursor
}
```

Time-driven, no request. This is the **correctness guarantee** that makes dropped webhooks non-fatal; webhooks are the latency optimization layered on top.

---

## What is intentionally NOT in this API (MVP)

- **No user-facing "sync now" endpoint.** A user does not manually trigger ingestion; the three internal sources cover every case. (A debug/admin re-sync trigger could be added later, behind auth — not in MVP.)
- **No endpoint to read raw payloads or sync jobs.** `RawActivityPayload` and `SyncJob` are internal state. Activities are read from **Workout Catalog**'s API once normalized, not from Ingestion. Ingestion has no "list my activities" surface — that would duplicate Catalog and leak raw Strava shape.
- **No backfill-progress endpoint.** "Imported 124 of 540" is pushed to the user via **SSE from Notifications**, not polled from Ingestion (see [sse-streaming.md](../../technical-notes/sse-streaming.md)). Ingestion publishes the per-activity `ActivityIngestedEvent`s that ultimately drive that count; it does not serve the count itself.
- **No token endpoints.** Ingestion never exposes Strava tokens; it obtains them in-process via `TokenManager.getValidAccessToken` (IAM). No HTTP involved.
- **No deauthorization endpoint distinct from the webhook.** Deauth arrives on the same `POST /api/webhooks/strava` as everything else (it is just `aspect_type=update, object_type=athlete`); it is not a separate route. The revocation *decision* is IAM's; Ingestion only receives and routes the webhook.

---

## Error bodies

The JSON parts of this surface (the `400`/`401` on the webhook `POST`) follow the project-wide [API error conventions](../../conventions/api-errors.md) — RFC 7807 `application/problem+json`. In practice Strava does not consume our error bodies meaningfully (it acts on the status code alone), but we keep the shape consistent with the rest of the API rather than special-casing it. The subscription-validation `GET` returns the bare `hub.challenge` JSON Strava's protocol requires, not a Problem Details body, on success.

---

## Next documents in this BC

- Sequence diagrams: `diagrams/sequence/activity-ingestion-webhook.md` (webhook → SyncJob → Catalog → …), `initial-backfill.md` (paginated history sync on connect)
- [domain-model.md](domain-model.md), [database.md](database.md), [events.md](events.md) — already written
- [strava-rate-limits.md](../../technical-notes/strava-rate-limits.md) — *to be written* — budget tracking, backoff, fair-share (referenced by the worker)
