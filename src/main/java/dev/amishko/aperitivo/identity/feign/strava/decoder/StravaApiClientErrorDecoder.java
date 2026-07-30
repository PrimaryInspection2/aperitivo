package dev.amishko.aperitivo.identity.feign.strava.decoder;

import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

@Slf4j
public class StravaApiClientErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        HttpStatus status = HttpStatus.valueOf(response.status());

        log.error("Feign client error. Method: {}, Status: {}",
                methodKey, status);

        // Map status codes to exceptions
        return switch (status) {

            default -> new RuntimeException("Unexpected error: " + status);
        };
    }
}
