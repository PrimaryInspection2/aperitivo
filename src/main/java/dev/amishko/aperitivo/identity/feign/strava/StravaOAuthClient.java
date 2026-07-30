package dev.amishko.aperitivo.identity.feign.strava;

import dev.amishko.aperitivo.identity.dto.tokens.TokensDto;
import dev.amishko.aperitivo.identity.feign.strava.config.StravaOAuthClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "strava-oauth", url = "${aperitivo.provider.strava.oauth.url}", configuration = StravaOAuthClientConfiguration.class)
public interface StravaOAuthClient {

    @PostMapping("/oauth/token")
    TokensDto refreshAccessToken(@RequestParam("refresh_token") String refreshToken);
}
