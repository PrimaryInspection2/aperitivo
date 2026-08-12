# ADR 0010: Single canonical login provider; additional sources linked by an authenticated user

- **Status:** Superseded by [ADR 0011](0011-identity-provider-signup.md)
- **Date:** 2026-08-12
- **Superseded:** 2026-08-12

> **Superseded.** This ADR made **Strava** the single provider allowed to create a `User`. [ADR 0011](0011-identity-provider-signup.md) moves account creation to a dedicated identity provider (Google) and demotes Strava to a pure data source — the evolution this ADR itself named as "the cleanest long-term topology and the likely destination".
>
> Two parts of this ADR **survive unchanged** in ADR 0011 and are the reason it is superseded rather than reversed:
> - the separation of the **login flow** (`oauth2Login`) from the **link flow** (`oauth2Client`, which does not touch the `SecurityContext`);
> - the rule that **account identity is never inferred** — no email merging, conflicts answered `409`, enforcement server-side rather than by hiding UI.
>
> What changed is *which* provider may create a `User`, and therefore *when* a data source is attached.

## Context

[ADR 0004](0004-user-model-and-connected-sources.md) established the `User` (internal UUID) / `ConnectedSource` split, with `UNIQUE (provider, provider_user_id)` and an explicit note that the schema permits N sources per user while the MVP creates exactly one. [ADR 0009](0009-identity-spring-security.md) established that login is a Strava OAuth flow handled by Spring Security, after which we mint our own JWT.

Neither ADR says **how a second provider attaches to an existing user**. That gap is not theoretical — it decides the shape of the OAuth callback, and the callback is being written now.

The current callback resolves a user by `(provider, provider_user_id)` and creates a new `User` when there is no match. With one provider that is correct. With two it is a silent account-splitting bug: an existing Strava user who clicks "Sign in with Garmin" produces a **second, empty `User`**, because `(GARMIN, 5432)` matches nothing. Their workouts, samples, plans and notifications then live under two different `user_id` values — permanently, since `user_id` is the foreign key in all six bounded contexts and the payload key in every event ([events/conventions.md](../events/conventions.md)).

The failure mode is worth naming precisely, because it drives the decision:

- It is **silent**. Both logins "succeed". The user sees an empty account and concludes their data was lost.
- It is **user-triggered and likely**. The user does not remember which button they pressed months ago; a login page with N equivalent buttons guarantees this eventually.
- It is **effectively unrecoverable**. Merging two `User` rows after the fact is a cross-BC data migration, not a feature.

Underneath the bug are two genuinely different use cases sharing one code path:

| | Caller | `user_id` at callback time |
|---|---|---|
| **Authentication** (login / sign-up) | anonymous | unknown — must be resolved or created |
| **Source linking** (connect another provider) | already authenticated | already known — must **not** be re-derived |

There is also a framework constraint. In an `oauth2Login` flow the `SecurityContext` at callback time already holds the **new** provider's authentication, by design. "Who was logged in before the redirect?" therefore cannot be answered in the success handler — any linking built on `oauth2Login` must smuggle the pre-redirect identity through the authorization request and restore it afterwards.

## Decision

**Exactly one provider may create a `User`. Every other provider attaches to an existing `User` through an explicit link flow initiated by an authenticated caller.**

Three parts.

### 1. Strava is the canonical login provider

Strava is the only provider that can create a `User`, and the login page offers exactly one entry point. Other providers are **data sources only** — they can be linked, never used to sign up or sign in.

This distinguishes two roles that a list of OAuth buttons conflates:

- **Data sources** (Strava, Garmin, Wahoo, COROS …) — provide activity data, and happen to speak OAuth.
- **Identities** (Google, Apple, email, passkey) — establish who someone is, and provide no data.

Only an identity may create an account. In the MVP no dedicated identity provider exists, so Strava — which every target user necessarily has, since Strava is the platform's sole data source ([architecture/overview.md](../architecture/overview.md), [roadmap](../business/roadmap.md)) — plays that role.

### 2. Linking is a separate Spring Security flow, not a second login

| Flow | DSL | Filter | `SecurityContext` | May create `User` |
|---|---|---|---|---|
| **Login** | `oauth2Login` | `OAuth2LoginAuthenticationFilter` | replaced | yes (first login only) |
| **Link** | `oauth2Client` | `OAuth2AuthorizationCodeGrantFilter` | **untouched** | never |

`OAuth2AuthorizationCodeGrantFilter` performs the authorization-code exchange and stores an `OAuth2AuthorizedClient` **without authenticating anyone** ([Spring Security 7 reference](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/client/authorization-grants.html)). That is exactly the link semantics: the caller stays authenticated by our JWT, and we merely acquire provider tokens.

Because the link flow never touches the `SecurityContext`, the current `user_id` is read straight from the JWT. The identity-smuggling problem described in the context disappears rather than being solved.

The two flows are separated by redirect URI. `redirect-uri` is a property of a `ClientRegistration`, so a linkable provider gets one registration per flow:

```yaml
strava:          redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"    # login
garmin-connect:  redirect-uri: "{baseUrl}/connect/oauth2/code/{registrationId}"  # link
```

Service surface — two methods, never one:

```java
LoginResult login(ProviderIdentityProfile profile, TokensDto tokens);            // anonymous caller
void        linkSource(UUID userId, ProviderIdentityProfile p, TokensDto t);     // authenticated caller
```

REST surface (detailed in [identity-access/api.md](../contexts/identity-access/api.md)):

```
GET    /connect/{provider}              → 302 to provider           (authentication required)
GET    /connect/oauth2/code/{provider}  → framework → linkSource(...)
GET    /api/me/sources                  → connected sources
DELETE /api/me/sources/{provider}       → disconnect (markRevoked, USER_REQUESTED)
```

### 3. Account identity is never inferred

- **No implicit merge by email, ever.** A provider-supplied email address is unverified from our side. Auto-merging on it means anyone who can create a provider account carrying a victim's email address inherits that victim's Aperitivo account.
- **A provider identity already linked to a different `User` is a conflict, not a re-assignment.** `linkSource` responds `409` with `…/errors/source-already-linked` ([api-errors.md](../conventions/api-errors.md)). We do not move the source, and we do not merge the accounts.
- **The same provider already linked to this user is a reconnect, not a link** — the existing `ConnectedSource` is reactivated (partial unique index on `(user_id, provider) WHERE status <> 'REVOKED'`, see [identity-access/database.md](../contexts/identity-access/database.md)).
- **Enforcement is server-side.** The login chain rejects authenticated callers on `/oauth2/authorization/**`; the connect chain requires authentication. Which buttons the login page renders is presentation, not a control — `/oauth2/authorization/{provider}` stays reachable by bookmark, stale tab or redirect regardless of what the UI shows.

### MVP scope

The MVP has one provider, so the link flow cannot yet be exercised. It ships as a **documented seam**, not as running code. What ships now is the part that prevents the bug from ever being written: the `login` / `linkSource` naming split, the single-registration login chain, and the authenticated-caller guard.

## Alternatives considered

- **Every provider can log in, plus an account-recovery UX** ("we found an account with this email — sign in that way and link this source"). Rejected for the MVP. It needs verified-email matching, an explicit confirmation step, and a recovery UI — and it still uses email as a join key, which no provider guarantees to be present, stable, unique or verified. Worth reconsidering once a dedicated identity provider exists, at which point it becomes a much smaller feature.
- **Implicit account merge by email at callback time.** Rejected outright — a textbook account-takeover vector, as described above. Cheap to implement, catastrophic and irreversible when wrong.
- **Post-hoc account-merge tooling.** Rejected. `user_id` is the FK in all six BCs and appears in every event payload; a merge means transactionally rewriting workout, sample, plan and notification rows across module boundaries — precisely the coupling the Modulith structure exists to prevent ([ADR 0005](0005-bounded-contexts.md)). Disproportionate cost for a problem that is entirely avoidable by construction.
- **Linking through `oauth2Login`, carrying the current `user_id` in the authorization request** (custom `OAuth2AuthorizationRequestResolver` / `AuthorizationRequestRepository`). Workable, and it was the obvious design before checking the framework. Rejected: it fights `oauth2Login`, whose whole purpose is to replace the authentication. We would have to capture the pre-redirect identity, restore it after the callback, and make sure the provider principal never leaks into the session — three chances to get authentication wrong. `oauth2Client` reaches the same outcome with none of them.
- **Hiding the other provider buttons for an authenticated user.** Rejected as *the* mechanism, kept as presentation. It is not a control: the authorization endpoint remains reachable. This ADR keeps the UI behaviour but grounds it in a server-side guard.
- **A dedicated identity provider (email or passkey) as the canonical account from day one, with all sport services demoted to pure sources.** The cleanest long-term topology and the likely destination. Rejected for the MVP on scope only: it adds a credential flow, a verification email and a recovery path to a product in which every user, by definition, already has a Strava account. [ADR 0004](0004-user-model-and-connected-sources.md) already keeps `User.email` nullable ("future: required for non-Strava IdPs") for exactly this evolution.

## Consequences

**Positive:**
- A duplicate account becomes **structurally impossible**, not merely unlikely: one code path creates a `User`, and it is reachable only by an anonymous caller through one provider.
- The link flow needs no identity smuggling — the framework's own separation of `oauth2Login` and `oauth2Client` matches the domain distinction exactly.
- The two flows are independently guarded, independently tested, and independently reasoned about.
- Moving later to a dedicated identity provider means adding a login mechanism and demoting Strava to a source. That is a change in the login chain, not in the data model — `User` / `ConnectedSource` already supports it.

**Negative:**
- A user who owns a Garmin but no Strava account cannot sign up. Accepted: the MVP is Strava-first by definition, and such a user is outside the target segment until a canonical identity provider ships.
- Two `ClientRegistration` entries per linkable provider — some duplication in `application.yaml`. Honest duplication: the two registrations express two different intents.
- Losing access to the Strava account means losing access to the Aperitivo account. There is no second factor and no recovery path. Accepted for the MVP; the fix is a canonical identity provider, not a password reset bolted onto a passwordless design.

**Risks:**
- **Drift.** Someone adds a second provider to `oauth2Login` "because it is one line in the config", silently restoring the account-splitting bug. Mitigation: the login chain is configured with exactly one registration, and an integration test asserts that only the canonical provider is registered for login while linkable providers are not.
- **UI/enforcement divergence.** A future login page could render buttons the server rejects, or vice versa. Mitigation: the login page renders from the same single-registration source the chain is configured from.

**Follow-ups:**
- `OAuth2CallbackService.handleCallback` → `login`, returning `LoginResult(userId, newlyRegistered)`; `linkSource(...)` added as a documented, unimplemented seam.
- Login-chain guard: authenticated callers on `/oauth2/authorization/**` are redirected to the app home rather than starting a new OAuth flow.
- [identity-access/api.md](../contexts/identity-access/api.md) gains the `/connect/**` and `/api/me/sources` surface, marked post-MVP.
- [business/glossary.md](../business/glossary.md): add **canonical login provider** and **link flow** to the Identity & Access section.
- **Still open, deliberately out of scope here** (one decision per ADR): whether a reconnect after revoke reactivates the existing `ConnectedSource` row in place or inserts a fresh one. The current schema forces in-place, because `UNIQUE (provider, provider_user_id)` is **not** partial while `uq_connected_sources_active_per_user_provider` is. That contradicts [identity-access/events.md](../contexts/identity-access/events.md), which justifies `connectedSourceId` as a dedup key on the premise that "a reconnect produces a new source with a new id". Resolve as a schema change or a documentation fix.

## References

- [ADR 0004: Internal user UUID + ConnectedSource entity](0004-user-model-and-connected-sources.md)
- [ADR 0005: Bounded Context decomposition](0005-bounded-contexts.md)
- [ADR 0009: Identity via Spring Security with own JWT issuer](0009-identity-spring-security.md)
- [Identity & Access — domain model](../contexts/identity-access/domain-model.md), [database](../contexts/identity-access/database.md), [events](../contexts/identity-access/events.md), [api](../contexts/identity-access/api.md)
- [Strava OAuth flow](../integrations/strava/oauth-flow.md)
- [Spring Security 7 — Authorization Grant Support](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/client/authorization-grants.html)
- [Spring Security 7 — OAuth2 Login advanced configuration](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/login/advanced.html)
