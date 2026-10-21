package dev.amishko.aperitivo.identity.dto.tokens;

import java.time.Instant;

public record RefreshTokenDto(String token, Instant expiresAt) {
    @Override
    public String toString() {
        return "RefreshTokenDto{}";
    }
}
