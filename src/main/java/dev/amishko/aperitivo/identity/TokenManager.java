package dev.amishko.aperitivo.identity;

import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.dto.RevocationReason;
import dev.amishko.aperitivo.identity.dto.TokenUseFailureKind;
import dev.amishko.aperitivo.identity.dto.tokens.AccessTokenDto;

import java.util.UUID;

public interface TokenManager {

    AccessTokenDto getValidAccessToken(UUID userId);
    void markRevoked(UUID userId, RevocationReason reason);
    void recordSuccessfulUse(UUID userId);
    void recordFailure(UUID userId, TokenUseFailureKind kind);
}
