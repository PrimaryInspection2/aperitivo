# Runbook: Deauthorization Storm

**Alert:** spike in `ConnectedSource` REVOKED transitions (many users revoking at once).

## Symptom

A large number of users' Strava connections go `REVOKED` in a short window — e.g. after a Strava ToS
change, a publicized incident, or a bug on our side that prompts mass disconnects.

## Diagnose

1. **Source of the revocations:** are they arriving as **deauth webhooks** (`object_type=athlete,
   updates.authorized=false`) or as **401s** on data calls ([webhooks.md](../../integrations/strava/webhooks.md),
   [oauth-flow.md](../../integrations/strava/oauth-flow.md))? Both converge on REVOKED but indicate
   different causes.
   - Mass **webhooks** → users actively disconnecting (Strava-side event, ToS reaction).
   - Mass **401s** → possibly **our** problem: a signing/credential issue making all our Strava calls
     fail, *masquerading* as revocations. **This is the dangerous case** — don't mass-mark REVOKED if
     the tokens are actually fine and our client secret/credentials broke.
2. **Is our Strava app healthy?** Check the client secret / app status — a revoked or misconfigured app
   makes every call 401.

## Mitigate

- **Genuine mass deauth (webhooks):** the system handles each correctly — REVOKED, cancel sync jobs,
  notify user to reconnect. The storm is load, not error; ensure Notifications isn't overwhelmed (it
  dedups and retries). Nothing to "fix" — users chose to disconnect.
- **False storm (our credentials broke):** **do not** let the 401-handler mass-revoke valid connections.
  If our app's Strava credentials are the cause, fixing them restores all calls — the tokens were never
  actually invalid. This is why distinguishing webhook-deauth from 401-deauth in step 1 is critical.

## Resolve

- Genuine: subsides as the triggering event passes; revoked users reconnect over time.
- False: restore app credentials; affected sources that were wrongly marked may need re-validation on
  next use (a real refresh will succeed if the tokens were fine).

## Prevent

- Alert separately on **401-driven** vs **webhook-driven** REVOKED rates — they need opposite responses.
- Guard the 401→REVOKED path against our-side credential failures (e.g. a circuit breaker that
  distinguishes "this user's token is bad" from "every call is failing").
