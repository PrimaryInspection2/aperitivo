# ADR 0007: Server-Sent Events instead of WebSocket

- **Status:** Accepted
- **Date:** 2026-05-20
- **Revised:** 2026-10-19 — client-side SSE consumption aligned with [ADR 0012](0012-spa-mobile-stateless-api.md) (bearer-authenticated stream, fetch-based client)

## Context

Aperitivo needs server-to-client push for a small set of use cases. Enumerated across all roadmap phases, they are:

- Progress feedback during initial Strava backfill ("imported 124 of 540 activities").
- Notification of newly computed insights (`PersonalRecordSet`) without the client polling.
- Notification of sync completion after a webhook-triggered ingestion finishes.
- Scheduled-session reminders and in-app notifications generally.
- Notification that a Strava connection broke (`IntegrationRevoked`), prompting reconnect.

Reviewing every one of these: **all are one-directional (server → client), and none is latency-critical** (seconds-to-minutes tolerance everywhere). There is no use case — in MVP, Phase 2, or Phase 3 — that requires the client to push messages back to the server over a persistent connection. In other words, **no bidirectional channel is needed.**

The features that would need low-latency or bidirectional traffic — live workout streaming and social challenges with live leaderboards — are non-goals ([overview](../architecture/overview.md#non-goals)).

## Decision

Use **Server-Sent Events (SSE)** for all server-to-client push. WebSocket is not part of the stack.

Each authenticated client opens one long-lived SSE connection. The Notifications BC fans events into per-user SSE channels held in an in-memory `Map<UserId, List<SseEmitter>>` (single-instance MVP). Missed events while disconnected are recovered from the in-app inbox, not from a replayed stream.

The stream is an ordinary API endpoint authenticated with `Authorization: Bearer` ([ADR 0012](0012-spa-mobile-stateless-api.md)). The browser's native `EventSource` cannot set request headers, so clients consume the stream with a fetch-based SSE client (e.g. `@microsoft/fetch-event-source`), which implements reconnection with `Last-Event-ID` and lets the client refresh its access token before reconnecting. The token is checked once, at connect time.

## Alternatives considered

- **WebSocket.** Full-duplex, more flexible, more complex (framing protocol, upgrade handshake, heartbeats, manual reconnection/backpressure). Rejected: we have no bidirectional traffic in any phase. Adding it for a unidirectional push problem is over-engineering and would read as such to a reviewer.
- **Polling.** Client polls `GET /notifications/recent` every N seconds. Rejected: worse latency, load proportional to users × frequency, and a DB query on every poll even when nothing changed.
- **Long polling.** A halfway house (parked requests via `DeferredResult`). Rejected: SSE solves the same problem with a standard protocol and built-in reconnection, at lower complexity.
- **Push via webhooks to a client-owned URL.** Not applicable — Aperitivo's clients are end-user browsers/apps, not backend systems.

## Consequences

**Positive:**
- Simpler than WebSocket: SSE is plain HTTP with `Content-Type: text/event-stream`. No framing protocol, no upgrade handshake, no extra library.
- Reconnection with the `Last-Event-ID` header is part of the protocol (implemented by the fetch-based SSE client).
- Works through HTTP-aware proxies. Spring MVC supports it natively via `SseEmitter`.
- Aligns cleanly with the chosen in-process architecture (Spring Modulith local events, single instance) — the emitter map lives in-process with no extra infrastructure. See [ADR 0008](0008-event-transport.md).

**Negative:**
- SSE behind buffering proxies needs buffering disabled (`X-Accel-Buffering: no` for nginx).
- SSE is text-only; binary payloads would need base64 (we have no binary push use case).
- In-memory emitter map is single-instance; horizontal scaling later needs sticky sessions or a Redis pub/sub fan-out (not in MVP).

**Risks:**
- A future feature genuinely requires bidirectional comms (e.g. a live coaching session in Phase 3+). Then we add a WebSocket endpoint **alongside** SSE — they coexist; this ADR does not preclude it.

**Follow-ups:**
- SSE endpoint design, in-memory emitter management, reconnect strategy, and proxy considerations: Notifications BC documentation and the SSE streaming technical note.

## References

- [ADR 0012: SPA and mobile clients; stateless API with ID-token login](0012-spa-mobile-stateless-api.md)
- [Architecture overview — non-goals](../architecture/overview.md#non-goals)
- [ADR 0008: Event transport](0008-event-transport.md)
- [HTML Living Standard — Server-Sent Events](https://html.spec.whatwg.org/multipage/server-sent-events.html)
