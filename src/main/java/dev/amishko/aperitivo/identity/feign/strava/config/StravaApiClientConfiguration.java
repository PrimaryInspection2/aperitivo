package dev.amishko.aperitivo.identity.feign.strava.config;

import dev.amishko.aperitivo.identity.feign.strava.decoder.StravaApiClientErrorDecoder;
import feign.Logger;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

public class StravaApiClientConfiguration {

    @Bean
    public ErrorDecoder stravaApiClientErrorDecoder() {
        return new StravaApiClientErrorDecoder();
    }

    @Bean
    public Retryer stravaApiClientRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Logger.Level stravaApiClientLoggerLevel() {
        return Logger.Level.BASIC;
    }
}
