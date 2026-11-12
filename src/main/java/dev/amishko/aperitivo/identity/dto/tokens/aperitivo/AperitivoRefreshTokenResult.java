package dev.amishko.aperitivo.identity.dto.tokens.aperitivo;

import java.util.UUID;

public record AperitivoRefreshTokenResult(AperitivoRefreshToken token, UUID userId) {
}
