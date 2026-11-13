package dev.amishko.aperitivo.identity.service.strava;

import dev.amishko.aperitivo.identity.dto.tokens.AccessTokenDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StravaCodeExchangeService {

    private final ClientRegistrationRepository clientRegistrationRepository;

    public AccessTokenDto exchangeToken(UUID userId, String code) {
        ClientRegistration stravaClientRegistration = clientRegistrationRepository.findByRegistrationId("strava");

        RestClientAuthorizationCodeTokenResponseClient tokenExchangeClient =
                new RestClientAuthorizationCodeTokenResponseClient();

        OAuth2AuthorizationRequest request = new OAuth2AuthorizationRequest.Builder()
                .authorizationUri("https://www.strava.com/oauth/authorize")
                .build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success("yep").build();

        OAuth2AuthorizationExchange exchange = new OAuth2AuthorizationExchange(request, response);
        OAuth2AuthorizationCodeGrantRequest oAuth2AuthorizationCodeGrantRequest = new OAuth2AuthorizationCodeGrantRequest(stravaClientRegistration, exchange);

        OAuth2AccessTokenResponse tokenResponse = tokenExchangeClient.getTokenResponse(oAuth2AuthorizationCodeGrantRequest);

        return new AccessTokenDto(tokenResponse.getAccessToken().getTokenValue(), tokenResponse.getAccessToken().getExpiresAt());
    }
}
