package dev.amishko.aperitivo.identity.web;

import com.nimbusds.jose.jwk.JWK;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@RestController
@RequiredArgsConstructor
public class OAuth2ConfigurationController {

    private final JWK aperitivoJwkConfig;

    @GetMapping(value = "/.well-known/jwks.json", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<JWK> getPublicCertificate() {
        return ResponseEntity.ok(aperitivoJwkConfig.toPublicJWK());
    }
}
