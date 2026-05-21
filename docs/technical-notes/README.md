# Technical Notes

Topic-focused deep dives that don't fit into a single Bounded Context or ADR.

These are longer-form explorations of specific technical areas — implementation patterns, schema designs, library choices, performance characteristics. They complement ADRs (which capture *decisions*) by capturing the *how*.

## Index

- [Token management](token-management.md) — *to be written* — TokenManager component design, concurrency, encryption, refresh patterns
- [TimescaleDB schema](timescaledb-schema.md) — *to be written* — hypertable design, retention policies, continuous aggregates
- [Kafka topology](kafka-topology.md) — *to be written* — topic conventions, partitioning, retention
- [Encryption at rest](encryption-at-rest.md) — *to be written* — AES-GCM column encryption, key management, rotation
- [Spring Modulith boundaries](spring-modulith-boundaries.md) — *to be written* — module structure, allowed dependencies, verification tests
- [Strava rate limit governance](strava-rate-limits.md) — *to be written* — budget tracking, backoff, fair-share across users
- [Idempotency and outbox patterns](idempotency-and-outbox.md) — *to be written* — concrete implementation patterns
- [JWT validation and claims](jwt-validation.md) — *to be written* — how BCs validate Keycloak-issued JWTs
