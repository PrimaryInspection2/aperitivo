package dev.amishko.aperitivo.identity.web;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@RestController
@RequiredArgsConstructor
public class OAuth2ConfigurationController {

    private final ImmutableJWKSet<SecurityContext> aperitivoJwkSet;

    @GetMapping(value = "/.well-known/jwks.json", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getJwkSet() {
        return ResponseEntity.ok(aperitivoJwkSet
                .getJWKSet()
                .toPublicJWKSet()
                .toJSONObject());
    }
}
