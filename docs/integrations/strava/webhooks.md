# Strava Integration: Webhook Configuration

How Aperitivo subscribes to and receives Strava webhooks — subscription setup, the validation
handshake, delivery format, and deauthorization. This is the **external-protocol reference** for
Strava's Webhook Events API; our *receipt and processing* is Ingestion's
([Ingestion api](../../contexts/activity-ingestion/api.md),
[webhook sequence](../../contexts/activity-ingestion/diagrams/sequence/activity-ingestion-webhook.md)).

## What webhooks are for

Webhooks are the **latency optimization** in ingestion: they tell us "activity X changed" within
seconds of it happening, so we fetch promptly rather than waiting for the next reconciliation sweep.
They are explicitly **not** the correctness guarantee — Strava webhooks can be lost, so periodic
reconciliation is mandatory ([architecture overview](../../architecture/overview.md)). Webhooks make
ingestion *fast*; reconciliation makes it *correct*.

## Subscription model

- **One subscription per application**, not per user. It is an app-global resource, created once per
  environment (dev, prod). All users' activity events arrive on the single subscription's callback
  URL.
- A subscription has a **callback URL** (our `POST /api/webhooks/strava`) and a **`verify_token`**
  (a secret we choose, used only in the validation handshake).
- Strava allows **one subscription per application** — you cannot have several; managing the single
  subscription's lifecycle (create, view, delete) is an operational task.

### Creating the subscription

```
POST https://www.strava.com/api/v3/push_subscriptions
  client_id, client_secret,
  callback_url = https://<our-host>/api/webhooks/strava,
  verify_token = <our secret>
```

Strava responds by **calling our callback URL** with a validation `GET` (below). Only if that
handshake succeeds is the subscription created and its `id` returned.

## The validation handshake (subscription-time `GET`)

When the subscription is created (and only then), Strava sends a one-time `GET` to the callback URL:

```
GET /api/webhooks/strava?hub.mode=subscribe&hub.challenge=<random>&hub.verify_token=<token>
```

Our handler must:
1. Check `hub.verify_token` equals the token we registered (reject otherwise).
2. Echo back the challenge as JSON:

```json
{ "hub.challenge": "<the random value Strava sent>" }
```

Strava confirms the echo and activates the subscription. **A failed handshake means the subscription
is never established** — so this endpoint must be correct before any webhook can arrive
([Ingestion api](../../contexts/activity-ingestion/api.md)).

> This is the **only** place `verify_token` is used. It guards the *handshake*, not subsequent
> deliveries — a crucial point for the trust model below.

## Delivery (the per-event `POST`)

Once active, Strava `POST`s to the callback URL for every relevant event:

```json
{
  "aspect_type": "create",        // create | update | delete
  "object_type": "activity",      // activity | athlete
  "object_id": 14207176594,       // the activity id (or athlete id for deauth)
  "owner_id": 134815,             // the athlete id
  "subscription_id": 120475,
  "event_time": 1718995800,
  "updates": {}                   // for update aspect: changed fields; for deauth: {"authorized":"false"}
}
```

Key delivery facts (full list in [api-quirks.md](api-quirks.md)):
- **Identifiers only** — never activity data. We re-fetch with our own credentials.
- **~2-second ack required** — respond `2xx` fast or Strava treats it as failed; chronic failure
  deactivates the subscription. This drives the receive-before-process design.
- **Up to 3 retries** on non-`2xx` — a backstop, not a durability substitute.
- **At-least-once, possibly out-of-order** — duplicates and reordering happen.

## Trust model — why weak inbound verification is tolerable

Strava's webhook spec defines **no per-delivery signature**. The guards available with certainty are
weak:
- **`subscription_id` match** — accept only deliveries naming our subscription. But `subscription_id`
  is a guessable integer, not a secret — a **filter**, not authentication.
- **`verify_token`** — only used at handshake time, not on deliveries.

So the design **does not trust the webhook**. The real trust boundary is the **authenticated re-fetch
to Strava**: the webhook body carries only identifiers, and we fetch the actual activity with our own
OAuth token. A forged webhook can at most cause a wasted `GET` for some `object_id` — rate-limit-
bounded, and a non-existent id `404`s harmlessly. This is why weak inbound verification is acceptable
in MVP ([Ingestion api](../../contexts/activity-ingestion/api.md)).

### The `X-Strava-Signature` open question

Strava's official webhook **example code** reads an `X-Strava-Signature` header (HMAC-SHA256 over
`timestamp.body` with a signing secret), but this is **not in the main spec** and historically may
not have been sent. **Confirm against a live subscription at implementation time.** If present, HMAC
verification becomes the preferred cheap receipt-time guard and replaces reliance on
`subscription_id`; if absent, the authenticated re-fetch remains the trust boundary. Either way the
design doesn't assume it, so adding it is hardening, not a contract change. (Flagged in
[api-quirks.md](api-quirks.md) and the [Ingestion api](../../contexts/activity-ingestion/api.md).)

## Deauthorization via webhook

When an athlete **revokes** Aperitivo's access on Strava's side, Strava sends a webhook (not a
separate channel):

```json
{
  "aspect_type": "update",
  "object_type": "athlete",
  "object_id": 134815,
  "updates": { "authorized": "false" }
}
```

This arrives on the **same** `POST /api/webhooks/strava` endpoint. Ingestion receives it (durable
inbox, like any delivery), and during async processing **routes it to IAM**
(`ConnectedSourceService.markRevoked`) rather than creating a `SyncJob` — deauth is not activity
work. IAM marks the `ConnectedSource` `REVOKED` and emits `IntegrationRevoked`; Ingestion (reacting
to that event) cancels the user's pending sync jobs, and Notifications tells the user to reconnect.
The revocation *decision* is IAM's; Ingestion only receives and routes
([Ingestion domain-model](../../contexts/activity-ingestion/domain-model.md),
[token-management.md](../../technical-notes/token-management.md)).

This is the **webhook** path to revocation; the other is a `401` on a data call
([oauth-flow.md](oauth-flow.md)). Both converge on the same `REVOKED` transition.

## Operational notes

- **Subscription lifecycle is an ops task** — create on environment setup, view via
  `GET /push_subscriptions`, delete via `DELETE /push_subscriptions/{id}`. A deauthorization-storm or
  a callback-URL change are the scenarios that touch it (see the `deauthorization-storm` runbook in
  [operations](../../operations/README.md)).
- **Callback URL must be public HTTPS** and reachable by Strava — behind nginx with the SSE buffering
  note *not* applicable here (this is a plain `POST`, not a stream).
- **The 2-second budget is spent on our own work** — so the receive path does one blind insert and
  nothing else; all resolution is deferred ([Ingestion api](../../contexts/activity-ingestion/api.md)).

## Related

- [Activity Ingestion api](../../contexts/activity-ingestion/api.md) — the receive endpoint + handshake
- [webhook sequence](../../contexts/activity-ingestion/diagrams/sequence/activity-ingestion-webhook.md) — the full receipt→process flow
- [api-quirks.md](api-quirks.md) — delivery characteristics, the signature question
- [oauth-flow.md](oauth-flow.md) — the other revocation path (`401`) and deauthorize endpoint
- [token-management.md](../../technical-notes/token-management.md) — what revocation does to tokens
