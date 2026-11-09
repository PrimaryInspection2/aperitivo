package dev.amishko.aperitivo.identity.feign.strava;

import dev.amishko.aperitivo.identity.feign.strava.config.StravaApiClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "strava-api", url = "${providers.strava.api.url}", configuration = StravaApiClientConfiguration.class)
public interface StravaApiClient {

}
