package dev.amishko.aperitivo.identity.feign.strava;

import dev.amishko.aperitivo.identity.dto.tokens.TokensDto;
import dev.amishko.aperitivo.identity.feign.strava.config.StravaOAuthClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "strava-oauth", url = "${spring.security.oauth2.client.provider.strava.token-uri}", configuration = StravaOAuthClientConfiguration.class)
public interface StravaOAuthClient {

    @PostMapping
    TokensDto refreshAccessToken(@RequestParam("refresh_token") String refreshToken);
}
