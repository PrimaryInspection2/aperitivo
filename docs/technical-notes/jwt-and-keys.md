# Technical Note: JWT Issuance and Validation

How Aperitivo mints and validates its own identity tokens. Complements
[ADR 0009](../adr/0009-identity-spring-security.md) (the decision to issue our own JWT with
Spring Security instead of Keycloak), [ADR 0012](../adr/0012-spa-mobile-stateless-api.md) (login
is an ID-token exchange; the API is stateless) and [ADR 0013](../adr/0013-access-and-refresh-tokens.md)
(short-lived access tokens with rotating refresh tokens) with the *how* — key handling, claims,
validation, refresh and logout. The IAM domain model and API reference this note for the mechanics.

## What the token is

The **access token**: a standard OIDC-style JWT, signed **RS256** (asymmetric), with a
**15-minute** lifetime ([ADR 0013](../adr/0013-access-and-refresh-tokens.md) §1). Issued by IAM when
a client exchanges a verified Google ID token at `POST /api/auth/google`, and again on every
`POST /api/auth/refresh` ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md) §2); validated by
every BC acting as a Spring Security **resource server**. Because it is a conventional JWT, the
choice of issuer (us, today; Keycloak, hypothetically later) is invisible to API consumers — the
swap path that [ADR 0009](../adr/0009-identity-spring-security.md) preserves.

It is returned in the **JSON body** (`{ "accessToken", "tokenType": "Bearer", "expiresIn": 900 }`)
and sent on every API call as `Authorization: Bearer <token>`. The SPA keeps it **in memory only**
(never `localStorage`, never a cookie) and gets a new one through the refresh token
([below](#the-refresh-token)) after a reload or when it expires.

A Google ID token is **never** accepted in its place: it proves identity once, at
`/api/auth/google`, and is then discarded ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md) §2).

### Claims (minimal, by design)

```
sub  = user_id (UUID)          // our internal identity — NEVER providerUserId
iss  = https://aperitivo.<env> // issuer (us)
aud  = aperitivo-api           // audience: our API, so the token is useless elsewhere
iat  = issued-at (epoch s)
exp  = expiry (iat + 15 min)
jti  = random UUID             // unique token id, kept for future forced revocation
```

`sub` is always the internal `user_id`, never a provider's subject id — the reasoning is in the
[IAM domain model](../contexts/identity-access/domain-model.md#identity-tokens--the-sub-claim):
`providerUserId` is an attribute of a *provider account* (the Google `sub` on a `UserIdentity`, or a
data source's athlete id on a `ConnectedSource`), would differ across providers, and would break
identity the moment a user's provider set changes. The two `sub`s must never be conflated
([ADR 0011](../adr/0011-identity-provider-signup.md)): the provider's `sub` is `providerUserId`;
only the internal `user_id` is ever minted into our `sub`. **Nothing else** goes in: no email, name
or connected sources — a JWT is signed, not encrypted, its claims are frozen until `exp`, and it
travels with every request; profile data comes from `GET /api/me`
([ADR 0013](../adr/0013-access-and-refresh-tokens.md) §1). No roles/authorities claim in MVP (single
user type); when authorization arrives it is an additive claim.

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

Minting happens in the **login endpoint** `POST /api/auth/google`, after `GoogleIdTokenVerifier`
has verified the Google ID token and the login provisioning service
(`OAuth2GoogleLoginCallbackService.handleLogin`) has committed and returned its `LoginResult` —
outside that transaction, since the token is a transport concern of the endpoint
([ADR 0012](../adr/0012-spa-mobile-stateless-api.md) §2). The same issuer mints a fresh access token
on every `POST /api/auth/refresh`. There is no `oauth2Login`, no `AuthenticationSuccessHandler` and
no `HttpSession` in this path. The login transaction resolves the `User` by `(provider, sub)` in
`user_identities`, creating one (and a `UserRegisteredEvent`) on first sight; it **never** touches
`connected_sources` ([ADR 0011](../adr/0011-identity-provider-signup.md)):

```java
Instant now = Instant.now();
JwtClaimsSet claims = JwtClaimsSet.builder()
    .issuer(issuerUri)
    .subject(userId.toString())          // internal UUID
    .audience(List.of("aperitivo-api"))
    .issuedAt(now)
    .expiresAt(now.plus(tokenTtl))       // 15 min, config
    .id(UUID.randomUUID().toString())    // jti
    .build();
JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsaKey.getKeyID()).build();
String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
```

The `kid` (key id) in the header is what lets validators pick the right public key during
rotation (below).

## Validating — resource server

The API is one stateless, deny-by-default `SecurityFilterChain`
([ADR 0012](../adr/0012-spa-mobile-stateless-api.md) §3) acting as a Spring Security resource
server. **Our** decoder is passed explicitly, and a converter turns the validated JWT into a
`CurrentUser(UUID userId)` principal:

```java
http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
        .decoder(ourJwtDecoder)                       // validates our tokens only
        .jwtAuthenticationConverter(currentUserConverter)));
```

The decoder verifies against **our** public key (selected by `kid`) and adds `iss` and `aud`
validation on top of the default timestamp checks. How it is wired (from the in-process `RSAKey` or
from our JWKS) is decided at implementation time. Other modules depend on `CurrentUser` from IAM's
published API, never on JWT or OAuth types.

Validation checks **signature, `iss`, `aud` and `exp` only** — no database or Redis lookup
([ADR 0013](../adr/0013-access-and-refresh-tokens.md) §1). A failure → `401` with a Problem Details
body from a custom `AuthenticationEntryPoint`.

**Only our decoder is reachable from the resource server.** The Google ID-token decoder lives inside
`GoogleIdTokenVerifier` and is deliberately **not** a `JwtDecoder` bean, so the resource server can
never pick it up by accident; a test asserts that a valid Google ID token is rejected as a bearer
token on `/api/**` ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md) §Risks).

## Key handling

### MVP

The RS256 key pair is **sourced from environment/configuration** (a mounted PEM or an
env-provided key), loaded into an `RSAKey` at startup with a stable `kid`. This matches the
[ADR 0009](../adr/0009-identity-spring-security.md) consequence: "env-sourced key in MVP,
KMS/Vault-backed in production." The private key never leaves IAM; the public key is what
resource servers load.

### JWKS endpoint

IAM exposes the **public** key set at `GET /.well-known/jwks.json` (`permitAll` in the security
chain, [ADR 0012](../adr/0012-spa-mobile-stateless-api.md) §3). Resource servers that load keys from
JWKS fetch and cache it, selecting the key by `kid`. This is what makes key rotation operationally clean: publish
the new public key alongside the old, rotate the signing key, let old tokens validate against
the old key until they expire, then retire it. The JWKS endpoint serves **only public keys** —
never the private signing material.

### Rotation (production target)

1. Generate a new key pair with a new `kid`.
2. Publish both public keys in JWKS (overlap window).
3. Switch the encoder to sign with the new `kid`.
4. After the longest possible access-token lifetime (15 min, plus clock-skew tolerance) has
   elapsed, retire the old public key.

Refresh tokens are opaque and not signed ([below](#the-refresh-token)), so signing-key rotation does
not touch them. No access token is ever invalidated by rotation — validators pick the key by `kid`, and in-flight
tokens validate against the still-published old key until natural expiry. KMS/Vault-backed
key custody is the production hardening target, tracked alongside the envelope-encryption work
in [ADR 0003](../adr/0003-token-storage-strategy.md) — **not** MVP.

## The refresh token

The access token is short-lived by design; staying signed in for weeks is the job of a second,
different token ([ADR 0013](../adr/0013-access-and-refresh-tokens.md) §2–3). It is **not** a JWT and
has nothing to do with signing keys.

- **Opaque:** 256 random bits, base64url-encoded. Nothing in it needs to be self-describing, and
  revocation needs a server record anyway.
- **Stored only as a SHA-256 hash** in Postgres — a database leak yields nothing presentable:

  ```
  refresh_tokens(id, token_hash UNIQUE, user_id, family_id, expires_at,
                 used_at NULL, revoked_at NULL, created_at)
  ```

- **Transport (web):** cookie `refresh_token`; `HttpOnly; Secure; SameSite=Strict; Path=/api/auth`.
  No script can read it, and the browser sends it only to `/api/auth/*` from our own site. Native
  clients (post-MVP) receive it in the response body and keep it in Keychain/Keystore; the exact
  mechanism is specified when a native client exists.
- **Family:** one sign-in on one device starts a *token family*; every rotation stays in that family.
- **Lifetimes (initial values, to be tuned):** each token expires after **14 days** of inactivity; a
  family has an **absolute lifetime of 30 days**, after which the user signs in through Google again.

### Rotation and reuse detection

Each `POST /api/auth/refresh`:

1. looks up the presented token by hash and checks that it is unexpired, unused and not revoked;
2. marks it `used_at = now`;
3. issues a **new** refresh token in the **same family** and a new access token.

Presenting an **already-used** token means two parties hold it. The server cannot tell which one is
legitimate, so it **revokes the whole family** — both must sign in again, and only the legitimate
user can ([RFC 9700 §4.14](https://www.rfc-editor.org/rfc/rfc9700#section-4.14)). To keep two tabs
refreshing at the same moment from looking like reuse, the just-rotated token stays acceptable for a
**grace window of 10–30 s**, and the SPA shares a single in-flight refresh across tabs.

Why Postgres and not Redis: durability (a Redis restart would sign everyone out), relational queries
("all sessions of a user"), and a trivial load — about one indexed lookup per active user every
15 minutes ([ADR 0013](../adr/0013-access-and-refresh-tokens.md) §6). Rows past `expires_at` are
deleted by a periodic job.

## Logout / revocation

Logout ends a **refresh-token family**, not an access token
([ADR 0013](../adr/0013-access-and-refresh-tokens.md) §4):

- `POST /api/auth/logout` revokes the family of the presented refresh token and clears the cookie
  (`Max-Age=0` with the same `Path` and attributes). It identifies the session by the refresh
  cookie, so it works even when the access token has already expired, and it is idempotent.
- The client discards its in-memory access token and calls `google.accounts.id.disableAutoSelect()`
  so that Google One Tap does not sign the user straight back in. Our logout does not sign the user
  out of Google.
- **"Log out everywhere"** revokes every family of the user.
- **The access token stays valid for at most 15 minutes after logout.** This is accepted: logout ends
  a session; theft is handled by rotation and family revocation.

### No per-request denylist in the MVP

No `jti` denylist is checked on requests — validation stays signature-only
([ADR 0013](../adr/0013-access-and-refresh-tokens.md) §5). The `jti` claim is still minted so that a
future "revoke now" for bans or forced logout stays open, either as a short-lived denylist checked
after signature validation or as a per-user "tokens issued before T are invalid" watermark. Which
one, and where it is stored, is decided if and when the need arises.

## What is deliberately NOT here (MVP)

- **No re-login on access-token expiry.** The client calls `POST /api/auth/refresh` with the refresh
  cookie instead; the user returns to Google only when the family's absolute lifetime ends or the
  family is revoked ([ADR 0013](../adr/0013-access-and-refresh-tokens.md)).
- **No introspection endpoint for access tokens.** Access tokens are self-contained JWTs validated by
  signature. The refresh token is opaque, but it is only ever presented to `/api/auth/*`, never to a
  resource server.
- **No per-request DB or Redis lookup for identity.** The refresh store is touched once per refresh,
  not on every request; there is no `jti` denylist in the MVP.
- **No server-side session.** No `HttpSession`, no `JSESSIONID`
  ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md) §3).

## Observability

- Metrics: access tokens issued, refreshes, refresh-token reuse detections (family revocations),
  validation failures by reason (expired / bad-signature / wrong issuer or audience), JWKS fetch
  cache hit/miss on resource servers.
- Never log token values (access, refresh or Google ID tokens), refresh-token hashes or signing
  material. `sub` (a UUID) and `family_id` are fine to log for correlation.

## Related

- [ADR 0009: Identity via Spring Security](../adr/0009-identity-spring-security.md)
- [ADR 0012: SPA and mobile clients; stateless API with ID-token login](../adr/0012-spa-mobile-stateless-api.md)
- [ADR 0013: Short-lived access tokens with rotating refresh tokens](../adr/0013-access-and-refresh-tokens.md)
- [IAM domain model — the `sub` claim](../contexts/identity-access/domain-model.md)
- [IAM api.md — auth endpoints, refresh, logout](../contexts/identity-access/api.md)
- [IAM database.md — `refresh_tokens`](../contexts/identity-access/database.md)
- [Token management](token-management.md) — the *data* tokens (Strava), a separate concern
- [ADR 0003: Token storage strategy](../adr/0003-token-storage-strategy.md) — key-custody hardening
