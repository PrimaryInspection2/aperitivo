package dev.amishko.aperitivo.identity.feign.strava.config;

import dev.amishko.aperitivo.identity.feign.strava.decoder.StravaOAuthClientErrorDecoder;
import feign.Logger;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

public class StravaOAuthClientConfiguration {

    @Bean
    public ErrorDecoder stravaOAuthClientErrorDecoder() {
        return new StravaOAuthClientErrorDecoder();
    }

    @Bean
    public Retryer stravaOAuthClientRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Logger.Level stravaOAuthClientLoggerLevel() {
        return Logger.Level.BASIC;
    }
}
