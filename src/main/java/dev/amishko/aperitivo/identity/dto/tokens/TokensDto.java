package dev.amishko.aperitivo.identity.dto.tokens;

import java.util.List;

public record TokensDto(AccessTokenDto accessToken, RefreshTokenDto refreshToken, List<String> scopes) {
}




