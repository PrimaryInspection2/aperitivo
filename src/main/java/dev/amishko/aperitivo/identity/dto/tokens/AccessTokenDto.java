package dev.amishko.aperitivo.identity.dto.tokens;

import java.time.Instant;

public record AccessTokenDto(String token, Instant expiresAt) {
    @Override
    public String toString() {
        return "AccessTokenDto{}";
    }
}