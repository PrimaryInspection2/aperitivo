package dev.amishko.aperitivo.identity.mapper;

import dev.amishko.aperitivo.identity.dto.ConnectedSourceStatus;
import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.dto.tokens.TokensDto;
import dev.amishko.aperitivo.identity.repository.entity.ConnectedSourceEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ConnectedSourceEntityMapper {

    public ConnectedSourceEntity toEntity(UUID userId, String providerUserId, DataProvider provider, TokensDto tokensDto) {
        return ConnectedSourceEntity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .provider(provider)
                .providerUserId(providerUserId)
                .accessToken(tokensDto.accessToken().token())
                .accessTokenExpiresAt(tokensDto.accessToken().expiresAt())
                .refreshToken(tokensDto.refreshToken().token())
                .refreshTokenExpiresAt(tokensDto.refreshToken().expiresAt())
                .scopes(tokensDto.scopes())
                .status(ConnectedSourceStatus.ACTIVE)
                .build();
    }

    public void updateTokens(TokensDto tokensDto, ConnectedSourceEntity connectedSourceEntity) {
        connectedSourceEntity.setAccessToken(tokensDto.accessToken().token());
        connectedSourceEntity.setAccessTokenExpiresAt(tokensDto.accessToken().expiresAt());
        connectedSourceEntity.setRefreshToken(tokensDto.refreshToken().token());
        connectedSourceEntity.setRefreshTokenExpiresAt(tokensDto.refreshToken().expiresAt());
        connectedSourceEntity.setScopes(tokensDto.scopes());
    }
}
