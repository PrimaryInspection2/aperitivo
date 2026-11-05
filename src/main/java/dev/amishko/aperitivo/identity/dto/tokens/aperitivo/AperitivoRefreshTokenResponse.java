package dev.amishko.aperitivo.identity.dto.tokens.aperitivo;

import java.util.UUID;

public record AperitivoRefreshTokenResponse(AperitivoRefreshToken token, UUID userId) {
}
