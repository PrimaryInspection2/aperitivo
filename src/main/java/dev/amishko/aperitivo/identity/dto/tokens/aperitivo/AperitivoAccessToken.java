package dev.amishko.aperitivo.identity.dto.tokens.aperitivo;


public record AperitivoAccessToken (String accessToken, String tokenType, Long expiresIn) {
}
