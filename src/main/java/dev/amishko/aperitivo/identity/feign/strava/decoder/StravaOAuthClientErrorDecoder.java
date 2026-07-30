package dev.amishko.aperitivo.identity.feign.strava.decoder;

import dev.amishko.aperitivo.identity.exception.oauth.OAuthUnexpectedStatusException;
import dev.amishko.aperitivo.identity.exception.oauth.OAuthBadRequestException;
import dev.amishko.aperitivo.identity.exception.oauth.OAuthTransientException;
import dev.amishko.aperitivo.identity.exception.oauth.OAuthClientCredentialsException;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
public class StravaOAuthClientErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        int status = response.status();
        String body = extractResponseBody(response);

        log.error("Feign client error. Method: {}, Status: {}",
                methodKey, status);

        return switch (status) {
            case 400 -> new OAuthBadRequestException("Bad Request to Strava API");
            case 401 -> new OAuthClientCredentialsException("Unauthorized access to Strava API"); //todo add CircuitBraker in future on this
            case 429, 500, 502, 503, 504 -> new OAuthTransientException(status + " error from Strava API. Response body: " + body);
            default -> new OAuthUnexpectedStatusException("Unexpected error: " + status);
        };
    }

    private String extractResponseBody(Response response) {
        if (response.body() == null) {
            return "No response body";
        }
        try {
            return new String(response.body().asInputStream().readNBytes(8192), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.error("Failed to read response body", ex);
            return "Error reading response body";
        }
    }
}
