package dev.amishko.aperitivo.identity.service;

import dev.amishko.aperitivo.identity.dto.tokens.TokensDto;

import java.util.UUID;

public interface TokenStateManager {
    TokensDto readSnapshot(UUID userId);
    TokensDto persistRefreshed(UUID userId, TokensDto refreshed);
}
