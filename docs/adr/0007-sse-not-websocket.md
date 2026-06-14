# ADR 0007: Server-Sent Events instead of WebSocket

- **Status:** Accepted
- **Date:** 2026-05-20
- **Reviewed:** 2026-06-14 (context rewritten; decision unchanged)

## Context

Aperitivo needs server-to-client push for a small set of use cases. Enumerated across all roadmap phases, they are:

- Progress feedback during initial Strava backfill ("imported 124 of 540 activities").
- Notification of newly computed insights (`PersonalRecordDetected`, `TrendDetected`) without the client polling.
- Notification of sync completion after a webhook-triggered ingestion finishes.
- Scheduled-session reminders and in-app notifications generally.
- Notification that a Strava connection broke (`IntegrationRevoked`), prompting reconnect.

Reviewing every one of these: **all are one-directional (server → client), and none is latency-critical** (seconds-to-minutes tolerance everywhere). There is no use case — in MVP, Phase 2, or Phase 3 — that requires the client to push messages back to the server over a persistent connection. In other words, **no bidirectional channel is needed.**

(Note: an earlier draft of the stack carried WebSocket on the assumption of live challenge leaderboards. Social features and public challenges were subsequently ruled out — see [ADR 0006](0006-no-social-challenges.md) — and live-streaming was ruled out in [ADR 0001](0001-no-strava-live-streaming.md). This ADR no longer rests on that history: the decision below stands on the use-case analysis above, independent of the removed features.)

## Decision

Use **Server-Sent Events (SSE)** for all server-to-client push. WebSocket is not part of the stack.

Each authenticated client opens one long-lived SSE connection. The Notifications BC fans events into per-user SSE channels held in an in-memory `Map<UserId, List<SseEmitter>>` (single-instance MVP). Missed events while disconnected are recovered from the in-app inbox, not from a replayed stream.

## Alternatives considered

- **WebSocket.** Full-duplex, more flexible, more complex (framing protocol, upgrade handshake, heartbeats, manual reconnection/backpressure). Rejected: we have no bidirectional traffic in any phase. Adding it for a unidirectional push problem is over-engineering and would read as such to a reviewer.
- **Polling.** Client polls `GET /notifications/recent` every N seconds. Rejected: worse latency, load proportional to users × frequency, and a DB query on every poll even when nothing changed.
- **Long polling.** A halfway house (parked requests via `DeferredResult`). Rejected: SSE solves the same problem with native browser support and built-in reconnection, at lower complexity.
- **Push via webhooks to a client-owned URL.** Not applicable — Aperitivo's clients are end-user browsers/apps, not backend systems.

## Consequences

**Positive:**
- Simpler than WebSocket: SSE is plain HTTP with `Content-Type: text/event-stream`. No framing protocol, no upgrade handshake, no extra library.
- Built-in reconnection with the `Last-Event-ID` header; native browser `EventSource` API.
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

- [ADR 0001: No Strava live-streaming](0001-no-strava-live-streaming.md)
- [ADR 0006: No social/challenges](0006-no-social-challenges.md)
- [ADR 0008: Event transport](0008-event-transport.md)
- [HTML Living Standard — Server-Sent Events](https://html.spec.whatwg.org/multipage/server-sent-events.html)
