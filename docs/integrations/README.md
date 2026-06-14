# Integrations

External systems Aperitivo integrates with, and the specifics of how.

## Strava

The primary (and only MVP) external dependency. See [strava/](strava/) for:

- **[OAuth flow](strava/oauth-flow.md)** — *to be written* — authorization, callback, code exchange, refresh
- **[API quirks](strava/api-quirks.md)** — *to be written* — rate limits, webhook delivery characteristics, undocumented behaviors
- **[Data model mapping](strava/data-model-mapping.md)** — *to be written* — how Strava's activity JSON maps to our canonical Workout
- **[Webhook configuration](strava/webhooks.md)** — *to be written* — subscription setup, verification, deauth handling

Identity is handled in-app by Spring Security (Strava OAuth2 login + our own JWT issuer) — there is no external identity server. See [ADR 0009](../adr/0009-identity-spring-security.md).

## Future providers

Additional OAuth-based, webhook-style data sources may be added post-MVP without schema migration (see [ADR 0004](../adr/0004-user-model-and-connected-sources.md)): Garmin Connect, Wahoo, COROS, Suunto, Polar, TrainingPeaks. Each would get its own subsection here describing OAuth, webhooks, and data mapping. Mobile-native sources (Apple Health, Google Fit) and file uploads (FIT/GPX/TCX) are different integration patterns and are not modeled as Providers.
