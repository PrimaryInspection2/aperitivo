# Identity & Access — Domain Model

The domain model for the Identity & Access (IAM) Bounded Context: the data it owns, the services that act on it, the events it emits, and the invariants that hold.

Style: Spring layered (see [ADR 0005](../../adr/0005-bounded-contexts.md)) — entities are plain data, services hold all logic. Naming follows [conventions/naming.md](../../conventions/naming.md).

## Responsibility recap

IAM answers three questions:
1. **Who is this user?** — the internal `UserEntity` (a stable UUID identity), reached through one or more `UserIdentityEntity` sign-in methods.
2. **What external sources have they connected, and are those connections healthy?** — `ConnectedSourceEntity` and its lifecycle.
3. **How do we authenticate them and let the rest of the system authorize them?** — Google ID-token login, then our own short-lived access token and rotating refresh token ([ADR 0012](../../adr/0012-spa-mobile-stateless-api.md), [ADR 0013](../../adr/0013-access-and-refresh-tokens.md)), surfaced to other modules as a `CurrentUser` principal.

### Identity is not a data source

The single most important distinction in this BC, and the one [ADR 0011](../../adr/0011-identity-provider-signup.md) exists to fix:

| | `UserIdentityEntity` | `ConnectedSourceEntity` |
|---|---|---|
| Answers | "how does this person sign in" | "where do we fetch training data from" |
| Provider | `IdentityProvider` — `GOOGLE` (future: `APPLE`) | `Provider` — `STRAVA` (future: `GARMIN`, …) |
| Tokens | **none stored** — discarded once the ID token is validated | the substance of the entity, AES-GCM encrypted |
| Status | it exists or it does not | `ACTIVE` / `REVOKED` / `ERROR`, `failureCount`, `lastError` |
| Read by | the login flow, once per login | `TokenManager` and Activity Ingestion, continuously |
| Created by | signup (anonymous caller) | the connect flow (authenticated caller) |

They share a shape — both are `(provider, providerUserId)` pointing at a `userId` — and nothing else. Merging them would subject sign-in methods to a revocation lifecycle, a refresh sweep and a failure counter that mean nothing for an identity, and would eventually let a token-refresh job try to refresh someone's ability to log in.

## Entities

Entities are plain data: fields, JPA/Hibernate mapping, audit/lifecycle annotations. No behavior.

### UserEntity

The internal identity record. Holds nothing provider-specific.

```java
@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    private UUID id;                 // internal identity, our UUID

    private String displayName;      // from the identity provider's profile at signup
    private String email;            // nullable; the account's contact address — see below
    private String avatarUrl;        // nullable; identity provider's picture claim

    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
}
```

`UserEntity` has **no** `status`, **no** tokens, and **no** provider columns — not `strava_*`, and equally not `google_sub`. Sign-in methods live on `UserIdentityEntity`; provider data concerns live on `ConnectedSourceEntity`.

`email` is the account's **single contact address**, seeded from the identity provider at signup. It has exactly one consumer: the Notifications BC, whose `EmailChannelAdapter` needs one address per user to deliver to — a "your Strava connection broke, please reconnect" mail has to go somewhere. That is the whole justification for the column; nothing else reads it. Notifications reaches it through the `UserContactReader` published port (below), never by querying this table.

It stays **nullable** even though Google always supplies a verified address. Tightening it to `NOT NULL` would block a future identity provider: Facebook documents that the field *"will not be returned if no valid email address is available"* (phone-only registration, or a declined `email` permission). Notifications must therefore treat "no address" as a normal state and fall back to the in-app channel, not as an error. See [ADR 0011](../../adr/0011-identity-provider-signup.md).

It is emphatically **not an identifier.** No lookup, join, or account match keys off it — see `providerUserId` below.

### UserIdentityEntity

A sign-in method: proof that some external identity provider vouches for this person. One user may have several (Google today, Apple later); each is unique across the system.

```java
@Entity
@Table(name = "user_identities")
public class UserIdentityEntity {
    @Id
    private UUID id;

    private UUID userId;                    // id-reference to UserEntity (NOT a JPA association)

    @Enumerated(EnumType.STRING)
    private IdentityProvider provider;      // GOOGLE (future: APPLE)

    private String providerUserId;          // the provider's `sub` claim — never the email

    @CreationTimestamp
    private Instant createdAt;

    private Instant lastLoginAt;            // nullable; updated on each successful login
}
```

**No tokens.** The identity provider's access and refresh tokens are worthless to us the moment the ID token is validated: Google supplies no training data, and we never call its API again. Storing them would create a secret with no purpose and a rotation obligation with no benefit.

**No email either.** The provider's email claim is read at login and written to `UserEntity.email`, which is the account's single contact address. Keeping a second per-identity copy would be a column with **no reader**: email is never a join key by decision (see below), so nothing may query it, and with one identity provider in MVP the value is by construction identical to `users.email`. It would only double the PII surface that account erasure has to reach. If a second provider ever makes "which provider asserted which address" worth answering, it returns as an additive nullable column.

**`providerUserId` is the provider's `sub`, never the email.** Google states plainly that the email claim *"may not be unique to this account and could change over time, therefore you shouldn't use this value as the primary identifier to link to your user record"*; Apple gives the same guidance, and its Hide My Email relay addresses are per-development-team anyway. This is what makes the email a display/contact attribute and nothing more — it never resolves, matches or merges accounts.

So the row holds exactly what a sign-in method *is*: which provider, which subject, and when it was last used.

### ConnectedSourceEntity

A credentialed link from a user to an external **data** provider, with its own lifecycle. Created by the connect flow, never by login.


```java
@Entity
@Table(name = "connected_sources")
public class ConnectedSourceEntity {
    @Id
    private UUID id;

    private UUID userId;                  // id-reference to UserEntity (NOT a JPA association)

    @Enumerated(EnumType.STRING)
    private Provider provider;            // STRAVA (future: GARMIN, …)

    private String providerUserId;        // e.g. strava_athlete_id, as string

    @Convert(converter = EncryptedBytesConverter.class)
    private String accessToken;           // stored AES-GCM encrypted (bytea on disk)
    @Convert(converter = EncryptedBytesConverter.class)
    private String refreshToken;          // stored AES-GCM encrypted (bytea on disk)
    private Instant accessTokenExpiresAt;

    @Type(StringArrayType.class)
    private String[] scopes;              // granted OAuth scopes (Postgres text[])

    @Enumerated(EnumType.STRING)
    private SourceStatus status;          // ACTIVE, REVOKED, ERROR

    private int failureCount;
    private String lastError;             // nullable

    private Instant lastRefreshedAt;      // nullable
    private Instant lastUsedAt;           // nullable

    @Version
    private long version;                 // optimistic lock — guards concurrent refresh

    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
}
```

### RefreshTokenEntity

One refresh token **we** issued — not Google's, not Strava's. Introduced by [ADR 0013](../../adr/0013-access-and-refresh-tokens.md). A chain of rotations shares a `familyId`, which stands for one sign-in on one device.

```java
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {
    @Id
    private UUID id;

    private byte[] tokenHash;             // SHA-256 of the opaque token — the token itself is never stored
    private UUID userId;                  // id-reference to UserEntity (NOT a JPA association)
    private UUID familyId;                // shared by every rotation of one sign-in

    private Instant expiresAt;            // idle expiry of this token
    private Instant usedAt;               // nullable; set when rotated
    private Instant revokedAt;            // nullable; set on logout or family revocation

    @CreationTimestamp
    private Instant createdAt;
}
```

Like the other entities it is plain data: whether a row is valid, whether a presentation is reuse, and when a family has outlived its absolute lifetime are decided in `RefreshTokenService`, below.

### Enums and value objects

No suffix (they are domain types, per the naming convention).

```java
public enum IdentityProvider {
    GOOGLE
    // future: APPLE
}

public enum Provider {
    STRAVA
    // future: GARMIN, WAHOO, COROS, SUUNTO, POLAR, TRAININGPEAKS
}

public enum SourceStatus {
    ACTIVE,    // tokens valid, sync allowed
    REVOKED,   // user/Strava revoked access; no sync; needs reconnect
    ERROR      // transient failure; retry policy applies
}

// immutable data holders (records)
public record StravaTokens(String accessToken, String refreshToken,
                           Instant expiresAt, String[] scopes) {}

public record StravaProfile(String athleteId, String displayName,
                            String email, String avatarUrl) {}

public record ProviderIdentityProfile(IdentityProvider provider, String providerUserId,
                              String email, String displayName, String avatarUrl) {}

public record LoginResult(UUID userId, boolean isNewUser) {}  // what login returns

public record StravaToken(String accessToken) {}   // what TokenManager hands to Ingestion

public record CurrentUser(UUID userId) {}          // the authenticated principal — IAM published API
```

`CurrentUser` is what every other module sees of authentication. The resource server converts our validated access token into it (`userId` = the token's `sub`); controllers in any BC take it as the principal and never touch JWT or OAuth types.

`IdentityProvider` and `Provider` are **two enums, deliberately**. A single merged enum would imply a `GOOGLE` value is a legal argument to `TokenManager.getValidAccessToken` or to `findByUserIdAndProvider` on `connected_sources` — the compiler should reject that, not a code reviewer.


## Persistence policy — Hibernate use in IAM

IAM deliberately uses **no JPA associations**. `ConnectedSourceEntity.userId` is a plain `UUID`, not a `@ManyToOne UserEntity`, and the same holds for `UserIdentityEntity.userId` and `RefreshTokenEntity.userId`. Navigation is via repository methods.

Rationale:
- `UserEntity` and `ConnectedSourceEntity` are **separate aggregates** with independent lifecycles ([ADR 0004](../../adr/0004-user-model-and-connected-sources.md)). DDD guidance: reference across aggregate boundaries by id, not by object association.
- Avoids any chance of tokens being pulled in transitively when reading a user (a `@OneToMany` would make that a footgun in a logger or serializer).
- Makes N+1 structurally impossible here: there is no lazy collection to trigger it. Reads are always explicit (`connectedSourceRepository.findByUserIdAndProvider(...)`).

This does **not** mean "no Hibernate." IAM uses the Hibernate features that *fit* an association-free design:
- `@Convert` with a custom `AttributeConverter` (`EncryptedBytesConverter`) for AES-GCM column encryption.
- A custom type for the Postgres `text[]` `scopes` column.
- `@Version` optimistic locking on `ConnectedSourceEntity` — directly relevant to concurrent token refresh (see [token-management.md](../../technical-notes/token-management.md)).
- Auditing via `@CreationTimestamp` / `@UpdateTimestamp`.

The full association toolkit (`@OneToMany`, fetch strategies, `@BatchSize`, entity graphs, cascades, orphan removal, second-level cache) is exercised in **Workout Catalog** and **Training Planning**, *within* their aggregates, where it is architecturally correct. Each such choice is documented with its rationale in those BCs.

## Repositories

Spring Data JPA, no logic.

```java
public interface UserRepository extends JpaRepository<UserEntity, UUID> {
}

public interface UserIdentityRepository extends JpaRepository<UserIdentityEntity, UUID> {
    Optional<UserIdentityEntity> findByProviderAndProviderUserId(IdentityProvider provider, String providerUserId);
    List<UserIdentityEntity> findByUserId(UUID userId);
    long countByUserId(UUID userId);          // guards "cannot remove the last sign-in method"
}

public interface ConnectedSourceRepository extends JpaRepository<ConnectedSourceEntity, UUID> {
    Optional<ConnectedSourceEntity> findByProviderAndProviderUserId(Provider provider, String providerUserId);
    Optional<ConnectedSourceEntity> findByUserIdAndProvider(UUID userId, Provider provider);
    List<ConnectedSourceEntity> findByUserId(UUID userId);
    List<ConnectedSourceEntity> findByStatus(SourceStatus status);
}

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {
    Optional<RefreshTokenEntity> findByTokenHash(byte[] tokenHash);
    List<RefreshTokenEntity> findByFamilyId(UUID familyId);     // family revocation
    List<RefreshTokenEntity> findByUserId(UUID userId);         // log out everywhere
    long deleteByExpiresAtBefore(Instant cutoff);               // cleanup job
}
```

## Services — where all logic lives

Six services, by responsibility.

### LoginService — signup and login

The IAM entry point for the **identity channel** (see "Three external channels" below). Runs once per login, after `GoogleIdTokenVerifier` has verified the ID token presented to `POST /api/auth/google`. It **never touches `connected_sources`**. (In the current code this service is `OAuth2GoogleLoginCallbackService.handleLogin`, the name [ADR 0012](../../adr/0012-spa-mobile-stateless-api.md) uses; its behaviour is unchanged by that ADR.)

```java
@Service
public class LoginService {

    @Transactional
    public LoginResult login(ProviderIdentityProfile identity) {
        // 1. resolve UserIdentityEntity by (provider, providerUserId)
        // 2. found      → touch lastLoginAt, return LoginResult(userId, false)
        //    not found  → create UserEntity + UserIdentityEntity,
        //                 publish UserRegisteredEvent,
        //                 return LoginResult(userId, true)
        // 3. the auth controller then issues our access + refresh tokens from the result
    }
}
```

Login never acquires, encrypts or stores provider tokens; it has no `ACTIVE`/`REVOKED`/`ERROR` to reason about and no reconnect branch. All of that belongs to `ConnectedSourceService`, reached through the connect flow, which is where a connection lifecycle belongs. A login is exactly what its name says.

**Concurrent first logins.** Two requests for the same new `sub` (a double click, two tabs) can both find no identity. The unique constraint on `(provider, provider_user_id)` rejects the second insert, its transaction rolls back entirely, and `@Retryable` — whose advice runs *outside* `@Transactional` — re-runs `login` in a new transaction, where the lookup now finds the winner and returns `LoginResult(userId, false)`. Exactly one user, one identity and one `UserRegisteredEvent` result (see [database.md](database.md#constraints-and-indexes)).

An unmatched `(provider, providerUserId)` **always** creates a new account. It is never matched against an existing user by email, and never merged — see the linking rules in [ADR 0011](../../adr/0011-identity-provider-signup.md).

#### Why it returns a value and not `void`

The published events do **not** carry the login result back to the caller, and cannot:

- **Different delivery semantics.** Events are `AFTER_COMMIT` + `@Async` — they run on another thread *after* the transaction commits, by which point the HTTP response is being written. A listener has no way to put a token into that response.
- **Different audience.** The events tell *other* BCs to react. Login itself is a synchronous request/response inside IAM: the auth controller needs the `user_id` **now**, in the same request, to mint the access token's `sub` and start a refresh-token family.
- **Self-consumption is a smell.** Per [events/conventions.md](../../events/conventions.md), a BC consuming its own events is a design smell; driving login off an IAM event would be exactly that.

So: "publish and forget" applies to the cross-BC side effects, not to the primary result of the use case.

The return type is a narrow `LoginResult` record, **not** the `UserEntity`. A JPA entity handed back across the transaction boundary is detached — any lazy access blows up, and it invites accidental serialization of persisted state into a response. `isNewUser` tells the caller whether this login was a signup without a second query. The onboarding branch itself — a newly registered user has **no data at all**, so they belong in the mandatory connect step rather than on an empty dashboard — is taken by the client, which routes to the connect step while `GET /api/me` returns an empty `sources` list (see [api.md](api.md)).

### UserIdentityService — sign-in methods

Owns the set of ways a given user can authenticate.

```java
@Service
public class UserIdentityService {
    List<UserIdentityEntity> findByUserId(UUID userId);
    UserIdentityEntity link(UUID userId, ProviderIdentityProfile identity);   // POST /api/me/identities/{provider}, authenticated
    void unlink(UUID userId, IdentityProvider provider);
}
```

Two rules it enforces, both from [ADR 0011](../../adr/0011-identity-provider-signup.md):

- **`link` never steals.** If `(provider, providerUserId)` already belongs to a different `userId`, it fails with a conflict rather than re-pointing the row — answered as `409 …/errors/identity-already-linked` (see [api.md](api.md)).
- **`unlink` never orphans.** Removing the last remaining sign-in method is rejected; `countByUserId` is the guard.

Linking is always initiated by an **authenticated** user from settings, presenting the new provider's ID token, which is verified by the same mechanism as login ([ADR 0012](../../adr/0012-spa-mobile-stateless-api.md) §5). There is no matching by email and no login-time merge prompt.

### UserService — identity CRUD

```java
@Service
public class UserService {
    UserEntity findById(UUID userId);
    UserEntity createFromProviderIdentityProfile(ProviderIdentityProfile profile);
    void updateProfile(UUID userId, ProfileUpdateDto update);
}
```

Knows nothing about tokens and nothing about data sources.

### ConnectedSourceService — connection lifecycle

Owns the whole `ConnectedSource` lifecycle: creation from the connect flow, reactivation on reconnect, and the `ACTIVE → REVOKED / ERROR` transitions. Emits `IntegrationConnectedEvent` and `IntegrationRevokedEvent`.

```java
@Service
public class ConnectedSourceService {
    ConnectedSourceEntity connect(UUID userId, Provider provider,
                                  String providerUserId, StravaTokens tokens);
    void markRevoked(UUID userId, Provider provider, RevocationReason reason);
    void markError(UUID userId, Provider provider, String error);
    void recordSuccessfulUse(UUID userId, Provider provider);
    List<ConnectedSourceEntity> findByUserId(UUID userId);
    Optional<ConnectedSourceEntity> findActive(UUID userId, Provider provider);
}
```

`connect` is the single entry point for the connect flow, and it covers three cases:

| Case | Effect | `IntegrationConnectedEvent` |
|---|---|---|
| No source for `(provider, providerUserId)` | create, `ACTIVE` | published |
| Source exists and is `ACTIVE` | refresh the stored tokens and scopes | **not** published — no transition |
| Source exists and is `REVOKED`/`ERROR` | refresh tokens, reset `failureCount`/`lastError`, set `ACTIVE` | published |

Writing fresh tokens onto a `REVOKED` row *without* restoring `ACTIVE` is the failure mode this table exists to prevent: the user would appear to reconnect successfully while `TokenManager` kept rejecting every request as inactive. The event fires on the **transition**, mirroring the guard in `markRevoked` — see invariant 6 and [events.md](events.md).

If `(provider, providerUserId)` is already connected to a **different** user, `connect` fails with a conflict (`409 …/errors/source-already-linked`) rather than moving the source.


### TokenManager — sole token gatekeeper

The only code in the entire system that reads, decrypts, or refreshes tokens. Activity Ingestion calls **only** this. Keeps the `Manager` suffix to signal that role (see naming conventions).

```java
@Service
public class TokenManager {
    StravaToken getValidAccessToken(UUID userId);     // hot path; lazy refresh under per-user lock
    void markRevoked(UUID userId, RevocationReason reason);
    void recordFailure(UUID userId, FailureKind kind);
    void recordSuccessfulUse(UUID userId);
}
```

Refresh mechanics (per-user lock, lazy vs proactive, 401 handling) are detailed in [token-management.md](../../technical-notes/token-management.md). This BC doc fixes only the contract.

### RefreshTokenService — our refresh-token lifecycle

Owns the server-side half of our session tokens ([ADR 0013](../../adr/0013-access-and-refresh-tokens.md)): issuing, rotating and revoking refresh tokens. It is the only code that reads or writes `refresh_tokens`, and it is reached only from `POST /api/auth/google`, `/api/auth/refresh` and `/api/auth/logout` — **never** on an ordinary API request, which is authenticated by the access token's signature alone.

```java
@Service
public class RefreshTokenService {
    IssuedRefreshToken startFamily(UUID userId);         // login: new family, new token
    RotationResult rotate(String presentedToken);        // refresh: validate, mark used, issue successor
    void revokeFamily(String presentedToken);            // logout — idempotent
    void revokeAllForUser(UUID userId);                  // log out everywhere
    void deleteExpired();                                // scheduled cleanup
}
```

The rules it enforces:

- **Only hashes are persisted.** The opaque token (256 random bits, base64url) is returned to the caller once; the row stores its SHA-256.
- **Rotation on every refresh.** The presented token must be unexpired (14-day idle expiry), unused, not revoked, and its family within the 30-day absolute lifetime. It is marked `usedAt = now`, and a successor with the **same `familyId`** is issued.
- **Reuse revokes the family.** A token whose `usedAt` is already set means two parties hold it; every row in the family is revoked, and both must sign in again. A 10–30 s grace window keeps two tabs refreshing at the same instant from tripping this ([RFC 9700 §4.14](https://www.rfc-editor.org/rfc/rfc9700#section-4.14)).
- **Logout is idempotent** and identifies the session by the refresh token, not by the access token, so it works after the access token has expired.

The method names and result types above are illustrative; the exact signatures are fixed at implementation time. Minting the access token that accompanies each refresh is `AccessTokenIssuer`'s job, not this service's.

### UserContactReader — the contact-address port

IAM's second published port, and the **only** way another BC obtains a user's contact address.

```java
public interface UserContactReader {
    Optional<String> emailFor(UUID userId);    // empty = this account has no address
}
```

Narrow on purpose: one method, one field, read-only, `Optional` rather than `null`. It is the `TokenManager` pattern applied to contact data — a stable interface in IAM's base package, implemented in `internal/`, called synchronously by exactly one consumer.

**Why a port and not an event field.** Notifications is the only BC that needs the address, but it consumes six events from four BCs. Carrying the address on those payloads would force Analytics, Ingestion, and Planning to know a user's email — data none of them has any business holding — and Spring Modulith **serializes event payloads into `event_publication`**, so every send would durably copy the address into the outbox as JSON. Account erasure would then have to reach into outbox blobs across four modules. A port keeps the address in exactly one column, in one table, in one BC.

**Why not a Notifications-owned projection.** A local copy is a third address to keep correct, needs a new "email changed" event to stay fresh, and reintroduces the same erasure surface. The port reads the live row instead.

**Read at delivery time, not at dispatch time.** Delivery is deferred — `DeliveryAttempt`s are enqueued `QUEUED` and drained later by a worker, with backoff retries in between. Resolving the recipient when the attempt is *created* would risk sending a retried message to an address the user has since changed, and would store a copy of it on `delivery_attempts`. So the address is resolved twice, for two different questions:

| Caller | Question | On empty `Optional` |
|---|---|---|
| `NotificationDispatchService` (gate) | does this account have an address at all? | skip the EMAIL channel — enqueue no attempt for it |
| `EmailChannelAdapter` (send) | what address, right now? | terminal failure for that attempt |

The gate check matters because `email` is nullable: without it, an addressless account would get a `QUEUED` EMAIL attempt that is guaranteed to fail, and Notifications would dutifully emit `NotificationFailed` for a situation that is not a failure. "No address" is a normal state, and the in-app channel is the fallback — see the `email` rationale above and [ADR 0011](../../adr/0011-identity-provider-signup.md).

### Supporting collaborators

- `GoogleIdTokenVerifier` — verifies the Google ID token presented at login (and, later, at identity linking) with Spring Security components: `NimbusJwtDecoder.withIssuerLocation("https://accounts.google.com")` for the signature against Google's JWKS, `JwtValidators.createDefaultWithValidators(new OidcIdTokenValidator(googleRegistration))` for `exp`/`iat`, `iss`, `aud` and `azp`, a `JwtClaimValidator` for `email_verified`, and a `nonce` comparison when the client supplied one. It maps the claims onto a `ProviderIdentityProfile`. Deliberately **not** a `JwtDecoder` bean, so the resource server can never pick it up. See [api.md](api.md).
- `AccessTokenIssuer` — mints our 15-minute RS256 access token with `JwtEncoder` (`sub` = `user_id`, `iss`, `aud` = `aperitivo-api`, `iat`, `exp`, `jti`). Called by the auth controller after login and after each refresh.
- The `CurrentUser` converter — turns a validated access token into the `CurrentUser(userId)` principal for the resource server.
- `RestClientAuthorizationCodeTokenResponseClient` (Spring Security) — exchanges the Strava authorization code presented to `POST /api/connect/strava`, using the existing `strava` `ClientRegistration`, so the `client_secret` never leaves the server.
- `StravaOAuthClient` — a thin HTTP wrapper over Strava's OAuth token endpoint (`POST /oauth/token`), used by `TokenManager` for refresh. Lives in IAM.
- `StravaApiClient` — used by the connect flow to read `GET /api/v3/athlete` and obtain the athlete id. This call is **explicit**: the code exchange yields tokens, and nothing fetches the profile for us.

## Three external channels — critical distinction

External systems reach us through **three independent channels**, owned by **different BCs** and carrying different meaning. Confusing them is a design error.

```mermaid
flowchart LR
    subgraph Identity provider
        GOOG["Google ID token<br/>POST /api/auth/google"]
    end

    subgraph Strava
        OAuth["authorization code<br/>POST /api/connect/strava"]
        WH[Webhook POST]
    end

    GOOG -- once per login, anonymous caller --> LS[IAM: LoginService.login]
    OAuth -- on demand, authenticated caller --> CSS2[IAM: ConnectedSourceService.connect]
    WH -- continuous, Strava-initiated --> WC[Ingestion: WebhookController]

    WC -- activities --> ING[ingest pipeline]
    WC -- "deauth (authorized=false)" --> CSS[IAM: ConnectedSourceService.markRevoked]
```

- **Identity channel** → IAM. A Google ID token, obtained by the client (Google Identity Services on the web, the native SDK on mobile) and presented to `POST /api/auth/google` by an **anonymous** caller. Verified by `GoogleIdTokenVerifier`, then creates or resolves a `User` in `LoginService.login`. Touches `users` and `user_identities` only (plus a new `refresh_tokens` family when our tokens are issued).
- **Connect channel** → IAM. A Strava authorization code, obtained by the SPA through a client-driven redirect (the SPA generates and verifies `state`) and presented to `POST /api/connect/strava` by an **already-authenticated** caller — the `user_id` comes from our access token, not from the provider. The backend exchanges the code server-side and calls `ConnectedSourceService.connect`, which creates or reactivates a `ConnectedSource`. Touches `connected_sources` only.
- **Webhook channel** → Activity Ingestion's `WebhookController`. Continuous, Strava-initiated. Carries activity create/update/delete **and** deauthorization (`aspect_type=update, updates.authorized=false`). Deauth is routed back to IAM's `ConnectedSourceService.markRevoked`, but it arrives via the webhook channel, not through either client-facing endpoint.

The first two both start in the client and end with the client calling our API, and superficially look alike. They are not: one is an OIDC ID-token exchange, the other an OAuth 2.0 authorization-code exchange with a confidential client; they start from different authentication states and write to different tables. Neither uses a backend OAuth redirect ([ADR 0012](../../adr/0012-spa-mobile-stateless-api.md)). See [api.md](api.md).

## Identity tokens — the `sub` claim

The access token we issue carries our **internal `user_id` (UUID)** as `sub`. Never `providerUserId`.

```
Access token (RS256 JWT, 15 min) claims:
  sub = user_id (UUID)          ← our stable internal identity
  iss = https://aperitivo.<…>   ← issuer (us)
  aud = aperitivo-api
  iat, exp (iat + 15 min), jti
```

Nothing else goes into it — no email, name or connected sources: a JWT is signed, not encrypted, its claims are frozen until `exp`, and it travels with every request. Profile data comes from `GET /api/me`. Staying signed in beyond 15 minutes is the job of the separate, opaque refresh token (`RefreshTokenService` above), which carries no claims at all. See [ADR 0013](../../adr/0013-access-and-refresh-tokens.md).

A **Google ID token is never accepted as an access token** on the API: its `sub` is Google's identifier, its lifetime is Google's, and we can neither revoke it nor add claims to it. It proves identity once, at login ([ADR 0012](../../adr/0012-spa-mobile-stateless-api.md)).

**Two different things are called `sub`, and they must never be conflated:**

| | Meaning | Where it lives |
|---|---|---|
| Our access token's `sub` | the internal `user_id` (UUID) | minted by us; the FK target across all six BCs |
| Google's `sub` | Google's subject identifier | stored as `UserIdentityEntity.providerUserId` |

Why our `sub` must be `user_id`:
- `sub` identifies the user **in our system**; our identity handle is `user_id`, stable for the life of the account.
- A provider's `sub` is an attribute of *one sign-in method*. A user with both Google and Apple has two of them — there is no single value to put in `sub`.
- `providerUserId` on a `ConnectedSource` is even less suitable: it identifies a *data source*, which the user may disconnect entirely without ceasing to exist.

No provider identifier ever enters the access token. The Strava athlete id is needed only by Activity Ingestion when talking to Strava, and is read from `ConnectedSourceEntity`.

Issuance/validation mechanics (RS256 keys, JWKS, lifetimes) are covered in [jwt-and-keys.md](../../technical-notes/jwt-and-keys.md).

## Events published

All via `ApplicationEventPublisher`, consumed after commit (`@TransactionalEventListener(AFTER_COMMIT)` / `@ApplicationModuleListener` on the consumer side). Events are records, not Spring `ApplicationEvent` subclasses.

| Event | Emitted by | When |
|---|---|---|
| `UserRegisteredEvent` | `LoginService` | a new `UserEntity` is created (first login) |
| `IntegrationConnectedEvent` | `ConnectedSourceService` | a `ConnectedSourceEntity` transitions to `ACTIVE` (connect or reconnect) |
| `IntegrationRevokedEvent` | `ConnectedSourceService` | a source transitions `ACTIVE → REVOKED` |

**These two never coincide.** "A user exists" and "a data source exists" are separate facts, separated by an arbitrary interval — signup, then whenever the user completes the connect step, possibly never. Consumers must not assume that a `UserRegisteredEvent` implies any source, and `IntegrationConnectedEvent` is the **sole** trigger for ingestion backfill. See [ADR 0011](../../adr/0011-identity-provider-signup.md).

Payload schemas: see [events.md](events.md).

## Events consumed

None from inside the system. External inputs are the Google ID token presented at login and the Strava authorization code presented at connect (both handled here) and the Strava deauthorization webhook (handled in Ingestion, routed back to `ConnectedSourceService`).

## Invariants

Enforced by services and the schema, not by entity behavior.

1. **`UserEntity` holds no provider-specific fields** — no `strava_*`, and no `google_sub` either. Enforced by code review and an architecture test asserting the field set.
2. **`(provider, provider_user_id)` is unique** in `user_identities` and, independently, in `connected_sources`. DB unique constraints; the resolving query checks before creating.
3. **A sign-in method belongs to exactly one user.** Linking an identity already bound elsewhere is a conflict, never a re-assignment.
4. **Every user has at least one sign-in method.** `unlink` refuses to remove the last one; otherwise the account becomes unreachable while its data lives on.
5. **At most one non-revoked `ConnectedSourceEntity` per `(userId, provider)`.** Enforced by a partial unique index on `(user_id, provider)` where `status <> 'REVOKED'`.
6. **Every transition *to* `ACTIVE` emits `IntegrationConnectedEvent`, and every transition to `REVOKED` emits `IntegrationRevokedEvent`** — on the transition only, never on a no-op re-application. Otherwise Ingestion would either never start syncing a reconnected source or keep running jobs against a dead one.
7. **Tokens never leave `TokenManager` in plaintext** except at the moment of a Strava API call. No other component decrypts them. Identity providers contribute no tokens at all.
8. **Concurrent refresh is serialized per user.** Guarded by `@Version` optimistic locking plus the per-user lock described in the token-management note.
9. **The contact address leaves IAM only through `UserContactReader`.** It is never placed on an event payload (which Modulith would persist into `event_publication`) and never copied into another BC's schema, so `users.email` stays the one row account erasure has to reach.
10. **A connected source's `userId` comes only from our access token.** The connect endpoint reads it from `CurrentUser`; the provider contributes the athlete id and the tokens, nothing else ([ADR 0011](../../adr/0011-identity-provider-signup.md), unchanged by [ADR 0012](../../adr/0012-spa-mobile-stateless-api.md)).
11. **Refresh tokens are persisted only as SHA-256 hashes, and presenting a used one revokes its whole family** (outside the short grace window). Enforced by `RefreshTokenService` ([ADR 0013](../../adr/0013-access-and-refresh-tokens.md)).

## Collaboration summary

```
Auth controller        → GoogleIdTokenVerifier, LoginService, AccessTokenIssuer, RefreshTokenService
LoginService           → UserService, UserIdentityRepository, (publishes UserRegisteredEvent)
UserIdentityService    → UserIdentityRepository
ConnectedSourceService → ConnectedSourceRepository, (publishes Integration* events)
UserService            → UserRepository
RefreshTokenService    → RefreshTokenRepository
TokenManager           → ConnectedSourceRepository, StravaOAuthClient
UserContactReader      → UserRepository (read-only)
Connect flow           → RestClientAuthorizationCodeTokenResponseClient, StravaApiClient, ConnectedSourceService
Activity Ingestion (other BC) → TokenManager.getValidAccessToken() ONLY
Notifications (other BC)      → UserContactReader.emailFor() ONLY
Every BC's controllers        → CurrentUser (the authenticated principal) ONLY
```

`TokenManager` and `ConnectedSourceService` are kept separate on purpose: `TokenManager` is a narrow, stable contract exposed to another BC (Ingestion) for hot-path token access; `ConnectedSourceService` is IAM-internal lifecycle management. Different audiences, different cadence of change. `UserContactReader` follows the same split against `UserService`, for the same reason.

## Next documents in this BC

- [database.md](database.md) — DDL, indexes, migrations (including `refresh_tokens`)
- [events.md](events.md) — event payload schemas
- [api.md](api.md) — REST endpoints (login, refresh, logout, connect, current user, sources, identities) and the security chain
- Sequence diagrams: [google-login.md](diagrams/sequence/google-login.md), [strava-connect.md](diagrams/sequence/strava-connect.md)
- [token-management.md](../../technical-notes/token-management.md) — already written

