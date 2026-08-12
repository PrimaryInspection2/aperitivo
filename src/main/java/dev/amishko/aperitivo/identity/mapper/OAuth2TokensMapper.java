package dev.amishko.aperitivo.identity.mapper;

import dev.amishko.aperitivo.identity.dto.tokens.AccessTokenDto;
import dev.amishko.aperitivo.identity.dto.tokens.RefreshTokenDto;
import dev.amishko.aperitivo.identity.dto.tokens.TokensDto;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class OAuth2TokensMapper {

    public TokensDto toTokens(OAuth2AuthorizedClient authorizedClient) {
        return new TokensDto(
                toAccessToken(authorizedClient.getAccessToken()),
                toRefreshToken(authorizedClient.getRefreshToken()),
                grantedScopes(authorizedClient));
    }

    private AccessTokenDto toAccessToken(OAuth2AccessToken accessToken) {
        return new AccessTokenDto(accessToken.getTokenValue(), accessToken.getExpiresAt());
    }

    private RefreshTokenDto toRefreshToken(OAuth2RefreshToken refreshToken) {
        return refreshToken == null
                ? null
                : new RefreshTokenDto(refreshToken.getTokenValue(), refreshToken.getExpiresAt());
    }

    private List<String> grantedScopes(OAuth2AuthorizedClient authorizedClient) {
        Set<String> granted = authorizedClient.getAccessToken().getScopes();
        return List.copyOf(granted.isEmpty() ? authorizedClient.getClientRegistration().getScopes() : granted);
    }
}
