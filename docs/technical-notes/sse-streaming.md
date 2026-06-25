# Technical Note: SSE Streaming

How Aperitivo pushes server-to-client messages with Server-Sent Events. Complements [ADR 0007](../adr/0007-sse-not-websocket.md) (the decision) with the *how*.

## Why SSE (recap)

Every push use case in Aperitivo is one-directional (server → client) and tolerant of seconds-to-minutes latency: backfill progress, new insights (`PersonalRecordSet`), sync completion, session reminders, revocation notices. No use case needs the client to push back over a persistent connection. SSE covers all of it with plain HTTP, native browser support, and built-in reconnection. See [ADR 0007](../adr/0007-sse-not-websocket.md).

## Endpoint

```
GET /api/sse/events
Authorization: Bearer <JWT>
Accept: text/event-stream
```

1. Spring Security validates the JWT and extracts `user_id`.
2. The controller creates an `SseEmitter` with a long timeout.
3. The Notifications BC registers the emitter in an in-memory `Map<UserId, List<SseEmitter>>` (a user may have several tabs/devices).
4. Spring MVC streams events as they are sent.

## Sending

When the Notifications BC handles a relevant domain event:
1. Resolve the target `user_id`.
2. Look up that user's emitters.
3. For each, `emitter.send(SseEmitter.event().name(type).id(eventId).data(payload))`.
4. On `IOException` (broken pipe), evict that emitter.

The `id` set on each message is what the browser echoes back as `Last-Event-ID` after a reconnect.

## Lifecycle and cleanup

- `emitter.onCompletion(...)` and `emitter.onTimeout(...)` evict the emitter from the registry.
- A failed `send` (client gone) also evicts.
- Without eviction the map would leak; this is the main correctness concern of the in-memory approach.

## Reconnection and missed messages

- The browser `EventSource` reconnects automatically and sends `Last-Event-ID`.
- MVP does **not** replay a precise event stream. Instead, unread notifications are persisted as an **in-app inbox**; on (re)connect the client fetches unread items via a normal REST call and then receives live updates over SSE. This keeps the streaming path stateless and simple while guaranteeing no user-visible loss.

## Proxy / infrastructure notes

- **nginx:** set `X-Accel-Buffering: no` on the SSE response (and `proxy_buffering off;` for the location) so events are not buffered.
- **Timeouts:** ensure proxy/load-balancer read timeouts exceed the heartbeat interval. Send a periodic comment line (`:\n\n`) as a heartbeat to keep idle connections alive.
- **Compression:** disable response compression for the stream.

## Multi-instance (deferred)

The in-memory registry is single-instance. When horizontal scaling arrives:
- **Sticky sessions** at the load balancer (route a user's SSE connection consistently), or
- **Redis pub/sub** fan-out: any instance publishes to a channel; the instance holding the user's emitter delivers.

Neither is in the MVP; the in-memory map is sufficient for a single-instance VPS deployment. See [ADR 0007](../adr/0007-sse-not-websocket.md) and [ADR 0008](../adr/0008-event-transport.md).

## Related

- [ADR 0007: SSE instead of WebSocket](../adr/0007-sse-not-websocket.md)
- [Notifications BC](../contexts/notifications/README.md)
