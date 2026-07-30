package dev.amishko.aperitivo.identity.dto;

public enum RevocationReason {
    DEAUTHORIZED,    // Strava deauthorization webhook (updates.authorized=false)
    TOKEN_REJECTED,  // a Strava API call returned 401 / invalid_token
    USER_REQUESTED   // user disconnected the source from within Aperitivo
}
