# Archived documentation

Documents in this folder are **frozen**. They record designs and plans that the project no longer follows, and they are kept only as history: they are not maintained, and links inside them may point to files that have since moved or changed.

**Do not implement anything from here.** The current model is described in the live documentation, starting with [ADR 0009](../adr/0009-identity-spring-security.md), [ADR 0011](../adr/0011-identity-provider-signup.md), [ADR 0012](../adr/0012-spa-mobile-stateless-api.md) and [ADR 0013](../adr/0013-access-and-refresh-tokens.md).

| Document | What it was | Replaced by |
|---|---|---|
| [adr/0002-identity-model-superseded.md](adr/0002-identity-model-superseded.md) | "Sign in with Strava" via a Keycloak custom Authenticator SPI | [ADR 0009](../adr/0009-identity-spring-security.md) |
| [adr/0010-single-canonical-login-provider-superseded.md](adr/0010-single-canonical-login-provider-superseded.md) | Strava as the single canonical login provider | [ADR 0011](../adr/0011-identity-provider-signup.md) |
| [diagrams/strava-oauth-login.md](diagrams/strava-oauth-login.md) | Sequence diagram of the "Sign in with Strava" flow | [google-login.md](../contexts/identity-access/diagrams/sequence/google-login.md), [strava-connect.md](../contexts/identity-access/diagrams/sequence/strava-connect.md) |
| [plans/identity-2026-10.md](plans/identity-2026-10.md) | Identity & Access implementation plan through three redesigns | [plans/identity.md](../plans/identity.md) |
| [plans/identity-adr-0011.md](plans/identity-adr-0011.md) | Sub-plan for the ADR 0011 migration | [plans/identity.md](../plans/identity.md) |
