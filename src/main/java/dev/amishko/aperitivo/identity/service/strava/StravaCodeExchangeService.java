package dev.amishko.aperitivo.identity.service.strava;

import dev.amishko.aperitivo.identity.dto.tokens.AccessTokenDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StravaCodeExchangeService {

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public AccessTokenDto exchangeToken(UUID userId, String code) {
        ClientRegistration stravaClientRegistration = clientRegistrationRepository.findByRegistrationId("strava");

        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId("strava")
                .attribute("code", code)
                .principal("code")
                .build();
        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
        OAuth2AccessToken accessToken = authorizedClient.getAccessToken();

        return new AccessTokenDto(accessToken.getTokenValue(), accessToken.getExpiresAt());
    }
}
