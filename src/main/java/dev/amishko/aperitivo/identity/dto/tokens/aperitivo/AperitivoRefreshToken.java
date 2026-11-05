package dev.amishko.aperitivo.identity.dto.tokens.aperitivo;


import java.time.Instant;

public record AperitivoRefreshToken(String refreshToken, Instant expiresAt) {
}
