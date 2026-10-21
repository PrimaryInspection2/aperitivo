# ADR 0011: Identity-provider signup (Google); data sources connected on demand

- **Status:** Accepted
- **Date:** 2026-08-12
- **Revised:** 2026-10-19 — login and connect mechanisms aligned with [ADR 0012](0012-spa-mobile-stateless-api.md)
- **Replaces:** [ADR 0010](../archived/adr/0010-single-canonical-login-provider-superseded.md) (archived — Strava as the single canonical login provider)
- **Related:** [ADR 0004](0004-user-model-and-connected-sources.md) (user model), [ADR 0009](0009-identity-spring-security.md) (identity in-app), [ADR 0012](0012-spa-mobile-stateless-api.md) (login and connect mechanisms)

## Context

Aperitivo's users are defined by Strava: they come for analytics on their Strava activity. The obvious design is to let Strava sign them in and create the account. Two facts rule that out.

**1. Strava cannot supply an email, so a Strava-provisioned account has nothing to match on.** Strava is OAuth 2.0, not OIDC: there is no ID token and no `email_verified`, and `GET /api/v3/athlete` does not return an email under the scopes we request. A Strava-provisioned `User` would have `email = NULL`. The moment a second sign-in method is ever added, the existing account has **no attribute a later login could be matched against**. Even a safeguard such as "link only after the user proves control of the existing account" cannot be offered, because there is nothing to prompt on. The duplicate is created silently, and it contains the user's real training history.

**2. Email is not a cross-provider linking key at all.** This is not our judgement; it is what the providers document:

| Provider | What the documentation says |
|---|---|
| **Google** | *"The value of this claim may not be unique to this account and could change over time, therefore you shouldn't use this value as the primary identifier to link to your user record."* Use `sub` instead. ([openid-connect](https://developers.google.com/identity/openid-connect/openid-connect)) |
| **Apple** | Hide My Email substitutes a relay address, *"the same for a user across all apps written by a single development team, and different for the same user across apps written by different development teams"*, ending in `@privaterelay.appleid.com` **or** `@icloud.com`. Apple likewise directs developers to `sub`. ([private email relay](https://developer.apple.com/documentation/signinwithapple/communicating-using-the-private-email-relay-service)) |
| **Facebook** | *"This field will not be returned if no valid email address is available."* An account can be registered with a phone number, and the `email` permission can be declined. ([Graph API user](https://developers.facebook.com/docs/graph-api/reference/user/)) |

So the real question is not "Strava or Google". It is **whether the account should be derived from a data source at all**. [ADR 0004](0004-user-model-and-connected-sources.md) answers the modelling half: `User` has its own UUID and holds nothing provider-specific. This ADR answers the provisioning half.

## Decision

**Account identity comes from an identity provider. Data sources are connected afterwards, on demand, by an already-authenticated user. The two concerns never share a table, a flow, or a lifecycle.**

### 1. Signup and login happen through Google, and only Google

Google is the sole identity provider in the MVP. It is OIDC, so it issues an ID token with a stable `sub` and an `email_verified` claim. The client obtains the ID token and the backend verifies it on `POST /api/auth/google`; the mechanism is [ADR 0012](0012-spa-mobile-stateless-api.md). A verified `(issuer, sub)` resolves the existing `User` or creates a new one together with its `UserIdentity`, and only a new account emits `UserRegisteredEvent`.

**Strava is not a login provider.** It never appears in `user_identities`, and "Sign in with Strava" is not part of the product.

### 2. `UserIdentity` is an entity in its own table

An identity is not a data source, and the two must not share `connected_sources`:

| | `user_identities` | `connected_sources` |
|---|---|---|
| Answers | "how does this person sign in" | "where do we fetch data from" |
| Tokens | not stored — discarded after login | the substance of the table |
| Status | exists or does not | `ACTIVE` / `REVOKED` / `ERROR`, `failure_count`, `last_error` |
| Read by | the login flow, once | `TokenManager` and Ingestion, continuously |
| Key | `(provider, provider_user_id)` = `(issuer, sub)` | `(provider, provider_user_id)` |

The key is **always `(issuer, sub)`, never email**. Email is mutable profile data, per the provider guidance quoted above.

### 3. Data sources are connected from inside the application

Connecting a data source is a separate flow, run by an authenticated user. The client runs the provider's consent redirect, and the backend exchanges the authorization code server-side ([ADR 0012](0012-spa-mobile-stateless-api.md) §4). **The `user_id` comes only from our token; the provider contributes the athlete and the tokens, nothing else.** Identity is never inferred from a data source.

```
POST   /api/connect/{provider}          → exchange code, create/reactivate ConnectedSource   (bearer required)
GET    /api/me/sources                  → connected data sources
DELETE /api/me/sources/{provider}       → disconnect (markRevoked, USER_REQUESTED)
```

### 4. Identity linking stays explicit, and is never inferred

- **Login never merges.** An unmatched `(provider, sub)` creates a new account. No interstitial, no email matching, no magic.
- **Adding a second sign-in method** (Apple, later) happens from settings, by an authenticated user (`POST /api/me/identities/{provider}`, [ADR 0012](0012-spa-mobile-stateless-api.md) §5).
- **A provider identity already bound to a different `User` is a conflict**, answered `409` with `…/errors/identity-already-linked`, never a re-assignment.
- **Invariant: a user cannot remove their last sign-in method.**

### 5. Onboarding has an explicit connect step

A newly registered user has no data at all. The connect step is therefore a **mandatory stage of onboarding**, not an option on the dashboard: while the user has no `ConnectedSource`, the client routes them to the connect step (`/connect/strava` in the SPA).

### 6. Consequence for the event flow

`UserRegisteredEvent` does not imply that a data source exists. It and `IntegrationConnectedEvent` fire from **different flows at different times**, and `IntegrationConnectedEvent` is the sole trigger for ingestion backfill. Signup and connecting a source are separate use cases ([use-cases.md](../business/use-cases.md)).

## Alternatives considered

- **Strava as the single login provider** (the archived [ADR 0010](../archived/adr/0010-single-canonical-login-provider-superseded.md)). Rejected for the two facts in the context: a Strava-provisioned account carries no email, so no proof-of-control safeguard can be offered, and the duplicate it fails to prevent contains real training history rather than nothing.
- **Allow both Strava and Google to log in.** Rejected: it creates duplicate accounts by exactly that route, and the Strava side cannot take part in any matching or prompting because it has no email. The worst of both topologies.
- **Store the Google identity as a `ConnectedSource` with `provider = GOOGLE`.** Rejected. [ADR 0004](0004-user-model-and-connected-sources.md) scopes that enum to *"OAuth-based, webhook-style partners"* with a token lifecycle for fetching data. Google supplies no data, and its tokens are dead weight after login. Sharing the table would subject identities to `ACTIVE`/`REVOKED`/`ERROR`, `failure_count`, `scopes`, the partial unique index, the proactive-refresh sweep and `TokenManager.findByUserIdAndProvider`. The end state is a refresh job trying to refresh a login, or `markRevoked` revoking someone's ability to sign in.
- **Put `google_sub` directly on `users`.** Rejected: it violates IAM invariant 1 (`UserEntity` holds no provider-specific fields), and Apple later would mean a second such column, the exact drift ADR 0004 warns about.
- **Email and password.** Rejected: passwordless is a deliberate property of the design ([ADR 0009](0009-identity-spring-security.md)). Passwords add reset flows, verification mail and a credential-breach surface for no gain here.
- **Auto-link on email match at login.** Rejected on the provider documentation quoted in the context. It would work only for Google↔Facebook, fail precisely for Apple's relay addresses, leak account existence and, worst, create the impression that duplicates are handled while a large fraction slip through silently.

## Consequences

**Positive:**
- **`User` is not derived from any provider.** ADR 0004's normalization holds in behaviour, not merely in schema.
- **A duplicate account is cheap.** Without a connected source a new account is *completely empty*: no workouts, no samples, no plans. A user who signs in the "wrong" way sees an empty dashboard rather than scrambled history, and the remedy is deleting a zero-activity row. This is the single largest argument for the topology.
- **Strava is replaceable.** If Strava withdraws its API, we lose a data source, not our user base.
- **Login is simple.** It never touches `connected_sources`, so it has no provider tokens, no `ACTIVE`/`REVOKED`/`ERROR` and no reconnect branch. That lifecycle lives in the connect flow, where it belongs.
- **Extension is additive.** Garmin is a connect flow; Apple is a row in `user_identities`. Neither is a migration.
- **Coach and multi-athlete scenarios are natural:** one identity, several sources, no identity semantics tangled in.

**Negative:**
- **An extra onboarding step, and the funnel cost is real.** The user came to see Strava analytics and is first asked to sign in with Google. Making connect a mandatory onboarding stage rather than a dashboard option reduces this, but does not eliminate it.
- **Losing the Google account means losing access**, even though the data is intact. Mitigation: Apple as a second sign-in method later, added from settings.
- **A Google dependency** for a product whose users are defined by Strava: a further external party in the critical path of every login.
- **More moving parts:** one more table and one more entity/repository pair.
- **`users.email` stays nullable.** Facebook may return no email at all, so a `NOT NULL` constraint would block a future provider. Requiring an email later would make it an onboarding field, not a provider claim.

**Risks:**
- **Drift towards a source-as-login.** Someone wires a data-source provider into `/api/auth/{provider}` because its `ClientRegistration` already exists. Mitigation: only identity-provider ID-token verifiers back the login endpoints, and a test asserts that no data-source provider is accepted for login.
- **Conflating the two `sub`s.** Our JWT `sub` is the internal `user_id` (UUID); the provider's `sub` is `providerUserId` on `UserIdentity`. Mitigation: the naming distinction is fixed in [naming.md](../conventions/naming.md) and restated in [domain-model.md](../contexts/identity-access/domain-model.md); nothing but `user_id` is ever minted into `sub`.
- **Provider claim-type quirks.** Apple returns `email_verified` and `is_private_email` as either strings or booleans. Mitigation: tolerant deserialization when Apple is added; not an MVP concern.

## References

- [ADR 0004: Internal user UUID + ConnectedSource entity](0004-user-model-and-connected-sources.md)
- [ADR 0005: Bounded Context decomposition](0005-bounded-contexts.md)
- [ADR 0009: Identity in-app with Spring Security and our own JWT issuer](0009-identity-spring-security.md)
- [ADR 0012: SPA and mobile clients; stateless API with ID-token login](0012-spa-mobile-stateless-api.md)
- [Google — OpenID Connect claims](https://developers.google.com/identity/openid-connect/openid-connect)
- [Apple — Communicating using the private email relay service](https://developer.apple.com/documentation/signinwithapple/communicating-using-the-private-email-relay-service)
- [Facebook — Graph API user reference](https://developers.facebook.com/docs/graph-api/reference/user/)
