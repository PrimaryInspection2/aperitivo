package dev.amishko.aperitivo.identity.dto;

import dev.amishko.aperitivo.identity.dto.tokens.TokensDto;

public record StravaOAuthResult(StravaProfile profile, TokensDto tokensDto) {
}
