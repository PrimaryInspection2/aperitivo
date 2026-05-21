# Integrations

External systems Aperitivo integrates with, and the specifics of how.

## Strava

The primary external dependency. See [strava/](strava/) for:

- **[OAuth flow](strava/oauth-flow.md)** — *to be written* — authorization, callback, code exchange, refresh
- **[API quirks](strava/api-quirks.md)** — *to be written* — rate limits, webhook delivery characteristics, undocumented behaviors
- **[Data model mapping](strava/data-model-mapping.md)** — *to be written* — how Strava's activity JSON maps to our canonical Workout
- **[Webhook configuration](strava/webhooks.md)** — *to be written* — subscription setup, signature verification, deauth handling

## Keycloak

Used for identity issuance. See [keycloak/](keycloak/) for:

- **[Custom Authenticator SPI](keycloak/authenticator-spi.md)** — *to be written* — design, deployment, lifecycle
- **[Realm configuration](keycloak/configuration.md)** — *to be written* — client setup, JWT claims, roles
