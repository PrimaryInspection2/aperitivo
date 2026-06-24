# Technical Notes

Topic-focused deep dives that don't fit into a single Bounded Context or ADR.

These are longer-form explorations of specific technical areas — implementation patterns, schema designs, library choices, performance characteristics. They complement ADRs (which capture *decisions*) by capturing the *how*.

## Index

- [Token management](token-management.md) — TokenManager component design, concurrency, AES-GCM encryption, lazy vs proactive refresh, revocation
- [SSE streaming](sse-streaming.md) — SseEmitter architecture, in-memory emitter registry, reconnect strategy, proxy considerations
- [TimescaleDB schema](timescaledb-schema.md) — hypertable design, retention policies, continuous aggregates
- [Encryption at rest](encryption-at-rest.md) — AES-GCM column encryption, key management, envelope encryption with Vault/KMS
- [Spring Modulith boundaries](spring-modulith-boundaries.md) — *to be written* — module structure, allowed dependencies, `ApplicationModules.verify()` in CI
- [Strava rate limit governance](strava-rate-limits.md) — *to be written* — budget tracking, backoff, fair-share across users
- [Idempotency and outbox patterns](idempotency-and-outbox.md) — *to be written* — concrete implementation patterns for Inbox/Outbox in Spring Modulith
- [JWT issuance and validation](jwt-and-keys.md) — JwtEncoder setup, RS256 key loading, JWKS endpoint, jti blacklist in Redis
