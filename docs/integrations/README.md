# Integrations

External systems Aperitivo integrates with, and the specifics of how.

## Strava

The primary (and only MVP) external dependency. See [strava/](strava/) for:

- **[OAuth flow](strava/oauth-flow.md)** — the **connect** flow: authorization, callback, code exchange, refresh, scopes, deauth
- **[API quirks](strava/api-quirks.md)** — rate limits, headers, pagination, webhook delivery, undocumented behaviors
- **[Data model mapping](strava/data-model-mapping.md)** — Strava activity JSON → canonical Workout, tier by tier
- **[Webhook configuration](strava/webhooks.md)** — subscription, validation handshake, delivery, deauth

Identity is handled in-app by Spring Security (Google OIDC login + our own JWT issuer) — there is no external identity server, and **Strava is a connected data source, not a login provider** ([ADR 0011](../adr/0011-identity-provider-signup.md)). See [ADR 0009](../adr/0009-identity-spring-security.md).

## Future providers

Additional OAuth-based, webhook-style data sources may be added post-MVP without schema migration (see [ADR 0004](../adr/0004-user-model-and-connected-sources.md)): Garmin Connect, Wahoo, COROS, Suunto, Polar, TrainingPeaks. Each would get its own subsection here describing OAuth, webhooks, and data mapping. Mobile-native sources (Apple Health, Google Fit) and file uploads (FIT/GPX/TCX) are different integration patterns and are not modeled as Providers.
