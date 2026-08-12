# ADR 0011: Identity-provider signup (Google); data sources connected on demand (supersedes ADR 0010)

- **Status:** Accepted
- **Date:** 2026-08-12
- **Supersedes:** [ADR 0010](0010-single-canonical-login-provider-superseded.md)
- **Amends:** [ADR 0004](0004-user-model-and-connected-sources.md) (provisioning trigger), [ADR 0009](0009-identity-spring-security.md) (which provider drives login)

## Context

[ADR 0010](0010-single-canonical-login-provider-superseded.md) made Strava the single provider allowed to create a `User`, with any additional data source attached through an authenticated link flow. It also named its own successor: its rejected alternative — "a dedicated identity provider as the canonical account, with all sport services demoted to pure sources" — was recorded as *"the cleanest long-term topology and the likely destination"*, deferred purely on MVP scope grounds.

Two findings moved that deferral forward.

**1. Strava cannot supply an email, so a Strava-provisioned account has nothing to match on.** Strava is OAuth 2.0, not OIDC: there is no ID token and no `email_verified`. `GET /api/v3/athlete` does not return an email under the scopes we request, and the implementation records this explicitly (`StravaUserMapper` maps the email to `null`). A Strava-provisioned `User` therefore has `email = NULL`.

Under ADR 0010 alone that was tolerable. The moment a second login provider is ever added it is not: the existing account has **no attribute a later Google login could be matched against**. ADR 0010's own safeguard — link only after the user proves control of the existing account — cannot even be *offered*, because there is nothing to prompt on. The duplicate is created silently, and it contains the user's real training history.

**2. Email is not a cross-provider linking key at all** — which removes the fallback that made a data-source-as-login topology survivable. This is not our judgement; it is what the providers document:

| Provider | What the documentation says |
|---|---|
| **Google** | *"The value of this claim may not be unique to this account and could change over time, therefore you shouldn't use this value as the primary identifier to link to your user record."* Use `sub` instead. ([openid-connect](https://developers.google.com/identity/openid-connect/openid-connect)) |
| **Apple** | Hide My Email substitutes a relay address, *"the same for a user across all apps written by a single development team, and different for the same user across apps written by different development teams"*, ending in `@privaterelay.appleid.com` **or** `@icloud.com`. Apple likewise directs developers to `sub`. ([private email relay](https://developer.apple.com/documentation/signinwithapple/communicating-using-the-private-email-relay-service)) |
| **Facebook** | *"This field will not be returned if no valid email address is available."* An account can be registered with a phone number, and the `email` permission can be declined. ([Graph API user](https://developers.facebook.com/docs/graph-api/reference/user/)) |

So the real question was never "Strava or Google". It was **whether the account should be derived from a data source at all**. [ADR 0004](0004-user-model-and-connected-sources.md) already answered the modelling half — `User` has its own UUID and holds nothing provider-specific. ADR 0010 nonetheless kept the *provisioning* of that `User` welded to a data source. This ADR finishes what ADR 0004 started.

## Decision

**Account identity comes from an identity provider. Data sources are connected afterwards, on demand, by an already-authenticated user. The two concerns never share a table, a flow, or a lifecycle.**

### 1. Signup and login happen through Google, and only Google

Google is the sole identity provider in the MVP. It is OIDC, so we get an ID token with a stable `sub` and a `email_verified` claim, and Spring Security handles it with `OidcUserService` — no custom `OAuth2UserService`, no profile round-trip.

**Strava is no longer a login provider.** It never appears in `user_identities`, and the string "Sign in with Strava" leaves the product.

### 2. `UserIdentity` is a new entity in its own table

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

The link flow is `oauth2Client`, whose `OAuth2AuthorizationCodeGrantFilter` performs the code exchange **without touching the `SecurityContext`** — the caller stays authenticated by our JWT, and the current `user_id` is read from it. This is the mechanism ADR 0010 established; it survives unchanged, and now carries all provider connections rather than only the second and subsequent ones.

```
GET    /connect/{provider}              → 302 to provider              (authentication required)
GET    /connect/oauth2/code/{provider}  → framework → linkSource(userId, …)
GET    /api/me/sources                  → connected data sources
DELETE /api/me/sources/{provider}       → disconnect (markRevoked, USER_REQUESTED)
```

### 4. Identity linking stays explicit, and is never inferred

This part of ADR 0010 is **retained verbatim** and is the reason that ADR is superseded rather than reversed:

- **Login never merges.** An unmatched `(provider, sub)` creates a new account. No interstitial, no email matching, no magic.
- **Adding a second sign-in method** (Apple, later) happens from settings, by an authenticated user, through the same link flow.
- **A provider identity already bound to a different `User` is a conflict**, answered `409` with `…/errors/identity-already-linked` — never a re-assignment.
- **Invariant: a user cannot remove their last sign-in method.**

### 5. Onboarding has an explicit connect step

A newly registered user has no data at all. The connect step is therefore a **mandatory stage of onboarding**, not an option on the dashboard: while the user has no `ConnectedSource`, the application routes them to `/connect/strava`.

### 6. Consequence for the event flow

`UserRegisteredEvent` no longer implies that a data source exists, so it is no longer adjacent to `IntegrationConnectedEvent`. The two now fire from **different flows at different times**, and `IntegrationConnectedEvent` becomes the sole trigger for ingestion backfill. UC-1 splits into a signup use case and a connect-source use case ([use-cases.md](../business/use-cases.md)).

## Alternatives considered

- **Keep ADR 0010 (Strava as the canonical login provider).** Rejected for the two findings in the context: a Strava-provisioned account carries no email, so ADR 0010's proof-of-control safeguard is unofferable, and the duplicate it fails to prevent contains real training history rather than nothing.
- **Allow both Strava and Google to log in.** Rejected: it reintroduces duplicate accounts by exactly the route ADR 0010 closed, and the Strava side cannot participate in any matching or prompting because it has no email. The worst of both topologies.
- **Store the Google identity as a `ConnectedSource` with `provider = GOOGLE`.** Rejected. [ADR 0004](0004-user-model-and-connected-sources.md) scopes that enum to *"OAuth-based, webhook-style partners"* with a token lifecycle for fetching data; Google supplies no data and its tokens are dead weight after login. Sharing the table would subject identities to `ACTIVE`/`REVOKED`/`ERROR`, `failure_count`, `scopes`, the partial unique index, the proactive-refresh sweep and `TokenManager.findByUserIdAndProvider`. The end state is a refresh job trying to refresh a login, or `markRevoked` revoking someone's ability to sign in.
- **Put `google_sub` directly on `users`.** Rejected: it violates IAM invariant 1 (`UserEntity` holds no provider-specific fields), and Apple later would mean a second such column — the exact drift ADR 0004 warned about.
- **Email and password.** Rejected: passwordless is a deliberate property of the design ([ADR 0009](0009-identity-spring-security.md)), and passwords add reset flows, verification mail, and a credential-breach surface for no gain here.
- **Auto-link on email match at login.** Rejected on the provider documentation quoted in the context. It would work only for Google↔Facebook, fail precisely for Apple's relay addresses, leak account existence, and — worst — create the impression that duplicates are handled while a large fraction slip through silently.

## Consequences

**Positive:**
- **`User` is no longer derived from any provider.** ADR 0004's normalization is now complete in behaviour, not merely in schema.
- **A duplicate account becomes cheap.** Without a connected source a new account is *completely empty*: no workouts, no samples, no plans. A user who signs in the "wrong" way sees an empty dashboard rather than scrambled history, and the remedy is deleting a zero-activity row. Under ADR 0010 the same mistake split real training data across two accounts. This is the single largest argument for the topology.
- **Strava becomes replaceable.** If Strava withdraws its API, we lose a data source, not our user base.
- **Login gets dramatically simpler.** It never touches `connected_sources`, so it has no tokens, no `ACTIVE`/`REVOKED`/`ERROR`, and no reconnect branch. All of that moves into the link flow, which is where it belongs — the login path stops carrying a lifecycle it never owned.
- **Extension is additive.** Garmin is a connect flow; Apple is a row in `user_identities`. Neither is a migration.
- **Coach / multi-athlete scenarios become natural** — one identity, several sources, no identity semantics tangled in.

**Negative:**
- **An extra onboarding step, and the funnel cost is real.** The user came to see Strava analytics and is first asked to sign in with Google. Mitigated by making connect a mandatory onboarding stage rather than a dashboard option, but not eliminated.
- **Losing the Google account means losing access**, even though the data is intact. Mitigation: Apple as a second sign-in method later, added from settings.
- **A Google dependency** for a product whose users are defined by Strava — a new external party in the critical path of every login.
- **More moving parts:** one more table, one more `ClientRegistration`, one more callback path, one more entity/repository pair.
- **`users.email` stays nullable.** We do not tighten it: Facebook may return no email at all, so a `NOT NULL` constraint would block a future provider. (Requiring an email later makes it an onboarding field, not a provider claim.)

**Risks:**
- **Drift back to a source-as-login.** Someone adds Strava to `oauth2Login` "because the `ClientRegistration` already exists". Mitigation: an integration test asserting the login chain exposes exactly the identity registrations, and that no data-source registration is reachable from the login base URI.
- **Conflating the two `sub`s.** Our JWT `sub` is the internal `user_id` (UUID); the provider's `sub` is `providerUserId` on `UserIdentity`. Mitigation: the naming distinction is fixed in [naming.md](../conventions/naming.md) and restated in [domain-model.md](../contexts/identity-access/domain-model.md); nothing but `user_id` is ever minted into `sub`.
- **Provider claim-type quirks.** Apple returns `email_verified` and `is_private_email` as either strings or booleans. Mitigation: tolerant deserialization when Apple is added; not an MVP concern.

**Follow-ups:**
- [ADR 0010](0010-single-canonical-login-provider-superseded.md) marked superseded and renamed.
- IAM BC documents rewritten: [domain-model](../contexts/identity-access/domain-model.md), [database](../contexts/identity-access/database.md) (`user_identities`), [events](../contexts/identity-access/events.md), [api](../contexts/identity-access/api.md), [README](../contexts/identity-access/README.md).
- Login sequence diagram split into a Google-login diagram and a Strava-connect diagram.
- [use-cases.md](../business/use-cases.md): UC-1 splits; the BC coverage matrix updated.
- [glossary.md](../business/glossary.md): `UserIdentity`, *identity provider*, *data source*, *link flow*.
- [integrations/strava/oauth-flow.md](../integrations/strava/oauth-flow.md) reframed as a **connect** flow rather than a login flow.
- Code: `OAuth2CallbackService.handleCallback` → `login` (Google) plus a separate `linkSource`; `user_identities` migration; connect security chain.

## References

- [ADR 0004: Internal user UUID + ConnectedSource entity](0004-user-model-and-connected-sources.md)
- [ADR 0005: Bounded Context decomposition](0005-bounded-contexts.md)
- [ADR 0009: Identity via Spring Security with own JWT issuer](0009-identity-spring-security.md)
- [ADR 0010: Single canonical login provider (superseded)](0010-single-canonical-login-provider-superseded.md)
- [Google — OpenID Connect claims](https://developers.google.com/identity/openid-connect/openid-connect)
- [Apple — Communicating using the private email relay service](https://developer.apple.com/documentation/signinwithapple/communicating-using-the-private-email-relay-service)
- [Facebook — Graph API user reference](https://developers.facebook.com/docs/graph-api/reference/user/)
- [Spring Security 7 — Authorization Grant Support](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/client/authorization-grants.html)
