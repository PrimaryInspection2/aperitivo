# Technical Note: JWT Issuance and Validation

How Aperitivo mints and validates its own identity tokens. Complements
[ADR 0009](../adr/0009-identity-spring-security.md) (the decision to issue our own JWT with
Spring Security instead of Keycloak) with the *how* — key handling, claims, validation, and
optional logout. The IAM domain model and API reference this note for the mechanics.

## What the token is

A standard OIDC-style JWT, signed **RS256** (asymmetric). Issued by IAM on successful Strava
OAuth login; validated by every BC acting as a Spring Security **resource server**. Because it
is a conventional JWT, the choice of issuer (us, today; Keycloak, hypothetically later) is
invisible to API consumers — the swap path that [ADR 0009](../adr/0009-identity-spring-security.md)
preserves.

### Claims (minimal, by design)

```
sub  = user_id (UUID)          // our internal identity — NEVER providerUserId
iss  = https://aperitivo.<env> // issuer (us)
iat  = issued-at (epoch s)
exp  = expiry (iat + 24–72h)
jti  = random UUID             // unique token id, for optional blacklist
```

`sub` is always the internal `user_id`, never the Strava athlete id — the reasoning is in the
[IAM domain model](../contexts/identity-access/domain-model.md#identity-tokens--the-sub-claim):
`providerUserId` is an attribute of a *connected source*, would differ across providers, and
would break identity the moment a user's provider set changes. No roles/authorities claim in
MVP (single user type); when authorization arrives it is an additive claim.

Why **asymmetric RS256, not symmetric HS256**: validation only needs the **public** key, so
resource-server BCs (and, later, separately deployed services) verify without holding signing
material. The private key lives in exactly one place — IAM. With HS256 every validator would
need the shared secret, multiplying the blast radius of a leak. RS256 keeps signing capability
to a single component.

## Issuing — `JwtEncoder`

Spring Security's `JwtEncoder` (backed by Nimbus) signs the token. Setup:

```java
@Bean
JwtEncoder jwtEncoder(RSAKey rsaKey) {
    JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(rsaKey));
    return new NimbusJwtEncoder(jwks);
}
```

Minting on successful login (inside `StravaOAuthService.handleCallback`, after the user is
provisioned/resolved):

```java
Instant now = Instant.now();
JwtClaimsSet claims = JwtClaimsSet.builder()
    .issuer(issuerUri)
    .subject(userId.toString())          // internal UUID
    .issuedAt(now)
    .expiresAt(now.plus(tokenTtl))       // 24–72h, config
    .id(UUID.randomUUID().toString())    // jti
    .build();
JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsaKey.getKeyID()).build();
String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
```

The `kid` (key id) in the header is what lets validators pick the right public key during
rotation (below).

## Validating — resource server

Each BC validates our JWT as a Spring Security resource server:

```java
http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
```

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: https://aperitivo.<env>     # validates iss + discovers JWKS
  # or, if not serving discovery: jwk-set-uri / public-key-location
```

Validation checks signature (against the public key matching the token's `kid`), `exp`, and
`iss`. A failure → `401`. On a protected endpoint, a token whose `jti` is blacklisted (below)
is also `401`.

## Key handling

### MVP

The RS256 key pair is **sourced from environment/configuration** (a mounted PEM or an
env-provided key), loaded into an `RSAKey` at startup with a stable `kid`. This matches the
[ADR 0009](../adr/0009-identity-spring-security.md) consequence: "env-sourced key in MVP,
KMS/Vault-backed in production." The private key never leaves IAM; the public key is what
resource servers load.

### JWKS endpoint

IAM optionally exposes the **public** key set at `/.well-known/jwks.json` (or
`/oauth2/jwks`). Resource servers configured with `issuer-uri`/`jwk-set-uri` fetch and cache
it, selecting the key by `kid`. This is what makes key rotation operationally clean: publish
the new public key alongside the old, rotate the signing key, let old tokens validate against
the old key until they expire, then retire it. The JWKS endpoint serves **only public keys** —
never the private signing material.

### Rotation (production target)

1. Generate a new key pair with a new `kid`.
2. Publish both public keys in JWKS (overlap window).
3. Switch the encoder to sign with the new `kid`.
4. After the longest possible token lifetime (≤72h) has elapsed, retire the old public key.

No token is ever invalidated by rotation — validators pick the key by `kid`, and in-flight
tokens validate against the still-published old key until natural expiry. KMS/Vault-backed
key custody is the production hardening target, tracked alongside the envelope-encryption work
in [ADR 0003](../adr/0003-token-storage-strategy.md) — **not** MVP.

## Logout / revocation (optional in MVP)

Our JWT is **stateless** — there is no server session to destroy ([IAM database](../contexts/identity-access/database.md):
"no `tokens` or `sessions` table"). Two postures:

- **Minimal MVP:** rely on short expiry. Logout clears the auth cookie
  (`Set-Cookie: token=; Max-Age=0`); a copied Bearer token simply expires on its own. Good
  enough when `exp` is short.
- **With immediate revocation:** maintain a **`jti` blacklist in Redis**. On logout, add the
  token's `jti` with a TTL equal to its *remaining* lifetime (`exp - now`), so the entry
  self-expires exactly when the token would have anyway — the blacklist never grows unbounded.
  Resource servers add one Redis check (`EXISTS denylist:jti:<jti>`) to the validation path;
  a hit → `401`.

```
logout:        SETEX denylist:jti:<jti> <remaining_seconds> 1
validation:    if EXISTS denylist:jti:<jti> → reject 401
```

Why Redis and not Postgres for the denylist: it is ephemeral, self-expiring, read on every
protected request, and never needs to be transactional with relational state — the same
reasoning that keeps the rate-limit budget in Redis. The denylist is bounded by
(logout rate × token lifetime), trivially small.

> The same `jti` mechanism is the natural hook for "log out everywhere" or admin-forced
> revocation later: blacklist by `sub` for a window, or keep a per-user "tokens issued before
> T are invalid" watermark. Not MVP; the `jti` claim is carried now so the option stays open.

## What is deliberately NOT here (MVP)

- **No refresh-token endpoint for our JWT.** On expiry the user re-authenticates via Strava (a
  redirect, no re-consent if the Strava grant is still valid). Silent refresh is a later
  optimization — see [IAM api.md](../contexts/identity-access/api.md).
- **No opaque tokens / introspection endpoint.** We use self-contained JWTs validated by
  signature, not reference tokens needing a central introspection call.
- **No per-request DB lookup for identity.** The token is self-contained; the only optional
  external check is the Redis `jti` denylist, and only when that feature is enabled.

## Observability

- Metrics: tokens issued, validation failures by reason (expired / bad-signature /
  blacklisted), JWKS fetch cache hit/miss on resource servers.
- Never log token values or signing material. `sub` (a UUID) is fine to log for correlation.

## Related

- [ADR 0009: Identity via Spring Security](../adr/0009-identity-spring-security.md)
- [IAM domain model — the `sub` claim](../contexts/identity-access/domain-model.md)
- [IAM api.md — auth endpoints, logout](../contexts/identity-access/api.md)
- [Token management](token-management.md) — the *data* tokens (Strava), a separate concern
- [ADR 0003: Token storage strategy](../adr/0003-token-storage-strategy.md) — key-custody hardening
