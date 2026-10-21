package dev.amishko.aperitivo.identity.config;


import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.amishko.aperitivo.identity.config.properties.JWKProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;

import java.time.Duration;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class AperitivoJWKConfig {

    private final JWKProperties JWKProperties;

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withPublicKey(JWKProperties.publicKey()).build();
        DelegatingOAuth2TokenValidator<Jwt> delegatingJwtValidator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(5)),
                new JwtAudienceValidator(JWKProperties.audience()),
                new JwtIssuerValidator(JWKProperties.issuer()));

        jwtDecoder.setJwtValidator(delegatingJwtValidator);
        return jwtDecoder;
    }

    @Bean
    JwtEncoder jwtEncoder() {
        JWK jwk = new RSAKey.Builder(JWKProperties.publicKey())
                .privateKey(JWKProperties.privateKey())
                .algorithm(JWSAlgorithm.RS256)
                .keyID(JWKProperties.keyId())
                .build();

        JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwks);
    }

}
