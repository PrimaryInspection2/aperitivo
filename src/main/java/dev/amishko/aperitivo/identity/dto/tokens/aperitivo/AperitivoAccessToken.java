package dev.amishko.aperitivo.identity.dto.tokens.aperitivo;

import java.time.Instant;

public record AperitivoAccessToken (String accessToken, String tokenType, Instant expiresIn) {
}
