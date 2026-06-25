# Notifications — API

The HTTP surface of the Notifications BC: the **SSE stream** for live in-app push, the **inbox**
(missed-message recovery + history), **mark-read**, and **preferences** CRUD. Errors follow
[RFC 7807](../../conventions/api-errors.md); auth is the project JWT (`sub` = `user_id`), every
endpoint scoped to the caller. SSE mechanics live in
[sse-streaming.md](../../technical-notes/sse-streaming.md); this document is the API contract.

## Design stance

Unlike Catalog/Analytics (read-only to clients, all writes event-driven), Notifications has a
small **write** surface — but only for *user-owned* data: preferences and read-state. Notifications
themselves are still produced only by consuming domain events; there is no "create a notification"
endpoint.

## `GET /api/sse/events` — the live stream

The single long-lived SSE connection per client. JWT-authenticated; the user's `user_id` is taken
from the token, never from a parameter.

```
GET /api/sse/events
Authorization: Bearer <JWT>
Accept: text/event-stream
```

- The controller creates an `SseEmitter` and registers it in the in-memory
  `Map<UserId, List<SseEmitter>>` (a user may hold several — multiple tabs/devices).
- Each pushed message: `event: <NotificationType>`, `id: <dedupKey>`, `data: <rendered JSON>`.
- The browser's `EventSource` reconnects automatically and sends `Last-Event-ID`.
- A periodic heartbeat comment (`:\n\n`) keeps idle connections alive through proxies.
- Behind nginx: `X-Accel-Buffering: no`, buffering off.

MVP does **not** replay a precise stream on reconnect. The client recovers anything missed while
disconnected from the **inbox** endpoint below, then resumes live updates. This keeps the streaming
path stateless. Full lifecycle/eviction detail:
[sse-streaming.md](../../technical-notes/sse-streaming.md).

## `GET /api/notifications` — the inbox (history + unread)

The in-app inbox: a user's notifications, newest first, with an unread filter. This is both the
history view and the missed-message recovery path after an SSE gap.

**Query parameters**

| Param | Type | Default | Notes |
|---|---|---|---|
| `unreadOnly` | bool | false | `true` hits the partial unread index |
| `page` | int | 0 | zero-based |
| `size` | int | 20 | capped (e.g. ≤100) |

**200**:

```json
{
  "content": [
    { "id": "a1…", "eventType": "PERSONAL_RECORD", "title": "New 5k PR", "body": "23:41 — 18s faster than your previous best", "readByUser": false, "createdAt": "2026-06-24T07:02:00Z" }
  ],
  "unreadCount": 3,
  "page": 0, "size": 20, "totalElements": 57, "totalPages": 3
}
```

`unreadCount` is the badge number; it is returned alongside the page so the client needs one call
to render both the list and the badge.

## `POST /api/notifications/{id}/read` — mark one read

Marks a single notification read (decrements the unread count). Idempotent — marking an
already-read notification is a no-op `204`. `404` if the notification does not exist or is not
owned by the caller (indistinguishable, by design — does not leak others' notifications).

## `POST /api/notifications/read-all` — mark all read

Marks all of the user's unread notifications read. Returns `200` with the new `unreadCount` (0).

## `GET /api/notifications/preferences` — read the subscription matrix

Returns the user's effective preferences (stored rows merged over code-defined defaults), so the
client renders a complete matrix even for a user who never changed anything.

**200**:

```json
{
  "preferences": [
    { "eventType": "PERSONAL_RECORD",     "channel": "EMAIL",  "enabled": true,  "isDefault": true },
    { "eventType": "PERSONAL_RECORD",     "channel": "IN_APP", "enabled": true,  "isDefault": true },
    { "eventType": "INTEGRATION_REVOKED", "channel": "EMAIL",  "enabled": true,  "isDefault": true },
    { "eventType": "SESSION_DUE",         "channel": "EMAIL",  "enabled": false, "isDefault": true }
  ]
}
```

`isDefault=true` means there is no stored row yet — the value shown is the code default. Toggling
it (below) creates the explicit row.

## `PUT /api/notifications/preferences` — update preferences

Upserts one or more `(eventType, channel) → enabled` entries for the user.

**Request**:

```json
{ "updates": [
  { "eventType": "SESSION_DUE", "channel": "EMAIL", "enabled": true },
  { "eventType": "INGESTION_FAILED", "channel": "IN_APP", "enabled": false }
] }
```

**200** returns the updated effective matrix (same shape as the GET). Each update upserts the
`(user_id, eventType, channel)` row (the unique constraint makes it an upsert). Unknown
`eventType`/`channel` → `400` (RFC 7807).

## Errors

| Status | When |
|---|---|
| `200 OK` | successful read / preference update |
| `204 No Content` | mark-read (including idempotent re-mark) |
| `400 Bad Request` | unknown eventType/channel, bad paging — RFC 7807 |
| `401 Unauthorized` | missing/invalid JWT (also closes/refuses the SSE stream) |
| `404 Not Found` | notification absent or not owned by caller (indistinguishable) |

## What is intentionally NOT in this API (MVP)

- **No "create notification" endpoint.** Notifications are produced only by consuming domain
  events — never by a client write.
- **No template-editing endpoints.** Templates are seed data in MVP; an admin editing surface is
  post-MVP.
- **No PUSH channel.** `Channel.PUSH` is deferred (needs mobile/web-push infra).
- **No delivery-status read endpoint per notification.** Delivery success/failure is internal +
  emitted as events for observability; the user sees the inbox, not per-channel attempt rows. (An
  admin/debug view could expose `delivery_attempts` later.)
- **No SSE replay endpoint.** Missed-message recovery is the inbox (`GET /api/notifications`), not a
  replayed stream.

## Next documents in this BC

- Sequence diagram: `diagrams/sequence/event-to-notification.md`
- [domain-model.md](domain-model.md), [database.md](database.md), [events.md](events.md) — already written
- [sse-streaming.md](../../technical-notes/sse-streaming.md) — SSE mechanics (already written)
