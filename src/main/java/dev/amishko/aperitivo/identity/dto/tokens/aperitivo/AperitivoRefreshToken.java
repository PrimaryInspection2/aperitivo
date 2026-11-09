package dev.amishko.aperitivo.identity.dto.tokens.aperitivo;


import java.time.Instant;

public record AperitivoRefreshToken(String refreshToken, Instant expiresAt) {

    // The raw token is a credential: keep it out of logs and exception messages.
    @Override
    public String toString() {
        return "AperitivoRefreshToken{expiresAt=" + expiresAt + "}";
    }
}
