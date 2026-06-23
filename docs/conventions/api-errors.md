# API Error Conventions

Cross-cutting rules for **all** Bounded Contexts in Aperitivo: how HTTP error responses are shaped. Every BC's REST surface follows these; per-BC `api.md` files reference this file rather than restating it.

The goal is a single, predictable error shape across the whole API, so a client never has to guess what an error body looks like depending on which endpoint it hit.

## Decision

**All JSON API error responses use [RFC 7807 — Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc7807).** The response carries `Content-Type: application/problem+json` and a standardized body.

This is supported by Spring Boot out of the box — enabled with a single property:

```properties
spring.mvc.problemdetails.enabled=true
```

With this on, Spring maps its standard exceptions (validation failures, `ResponseStatusException`, `404`, etc.) to `ProblemDetail` automatically. Our own exceptions are mapped the same way via `@ExceptionHandler` methods that return `ProblemDetail`.

## The Problem Details shape

```json
{
  "type": "https://aperitivo.<domain>/errors/integration-not-active",
  "title": "Integration not active",
  "status": 409,
  "detail": "The Strava connection for this user is REVOKED.",
  "instance": "/api/me"
}
```

| Field | Meaning | Our convention |
|---|---|---|
| `type` | URI identifying the *kind* of problem | kebab-case slug under `/errors/`, e.g. `…/errors/user-not-found`. `about:blank` for generic problems with no specific type (Spring's default) |
| `title` | Short, human-readable summary of the type | stable per `type`; does **not** vary per occurrence |
| `status` | HTTP status code, duplicated in the body | always mirrors the real HTTP status |
| `detail` | Human-readable explanation of *this* occurrence | safe to show a client; **never** leaks internals (see below) |
| `instance` | URI of the specific request that failed | the request path |

The `type` URI is a stable identifier, not necessarily a live page. It does not have to resolve to documentation in the MVP; the scheme (`https://aperitivo.<domain>/errors/<slug>`) is reserved so it can later point at real error docs without changing the contract.

## Conventions on top of the RFC

- **HTTP status is authoritative.** The `status` body field always equals the response status. They never disagree.
- **`type` slug naming.** kebab-case, matching the event logical-name style (`integration-not-active`, `user-not-found`). One `type` per distinct, client-meaningful error condition — not one per throw site.
- **`title` is stable, `detail` is specific.** `title` is fixed for a given `type` ("Integration not active"); `detail` describes the concrete instance ("…for this user is REVOKED").
- **Extension members are allowed.** RFC 7807 permits extra top-level fields. The standard case is **validation errors** (`400`): include an `errors` array of field violations.

  ```json
  {
    "type": "about:blank",
    "title": "Bad Request",
    "status": 400,
    "detail": "Validation failed for 1 field.",
    "instance": "/api/plans",
    "errors": [
      { "field": "startDate", "message": "must not be null" }
    ]
  }
  ```

## What must never appear in an error body

This is a security boundary, not a style preference:

- **No stack traces, no exception class names, no SQL.** `detail` is a sentence for a human, not a debugging dump.
- **No secrets or tokens.** Access/refresh tokens, `jti`, signing keys, the Strava client secret — never, in any field.
- **No sensitive internal structure.** Do not echo back raw provider ids, internal table names, or anything that maps the system's internals for an attacker.

Diagnostic detail belongs in **logs and OTel spans** (correlated by `trace_id`), not in the client-facing body. A client gets a stable `type` + a safe `detail`; an operator gets the full picture from telemetry.

## Scope and exceptions

- **JSON API endpoints** (everything under `/api/**`) use Problem Details.
- **Browser-driven OAuth endpoints** (`/oauth2/authorization/*`, `/login/oauth2/code/*`) **redirect** on failure (to the login page with an error indication) rather than returning `application/problem+json` — they are part of a browser navigation flow, not a JSON API. See [identity-access/api.md](../contexts/identity-access/api.md).
- **SSE stream** (`/api/sse/events`): connection-level failures use normal HTTP status on the initial handshake; once the stream is open, errors are delivered as in-band events, not Problem Details bodies. See [sse-streaming.md](../technical-notes/sse-streaming.md).

## References

- [RFC 7807 — Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc7807)
- [Spring Framework — Error Responses (`ProblemDetail`)](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- [conventions/naming.md](naming.md) — layer suffixes, kebab-case logical names
