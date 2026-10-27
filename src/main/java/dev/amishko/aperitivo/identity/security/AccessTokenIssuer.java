package dev.amishko.aperitivo.identity.security;


import dev.amishko.aperitivo.identity.config.properties.JWKProperties;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoAccessToken;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER;
import static org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256;

@Service
@RequiredArgsConstructor
public class AccessTokenIssuer {

    private final JWKProperties jwkProperties;
    private final JwtEncoder jwtEncoder;
    private final Clock clock;

    public AperitivoAccessToken issueAccessToken(UUID userId) {

        JwsHeader header = buildHeader();
        JwtClaimsSet claims = buildClaims(userId);

        JwtEncoderParameters jwtEncoderParameters = JwtEncoderParameters.from(header, claims);
        Jwt issuedJwt = jwtEncoder.encode(jwtEncoderParameters);
        Long expiresIn = Duration.between(issuedJwt.getIssuedAt(),issuedJwt.getExpiresAt()).toSeconds();

        return new AperitivoAccessToken(issuedJwt.getTokenValue(), BEARER.getValue(), expiresIn);
    }

    private JwtClaimsSet buildClaims(UUID userId) {
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plus(jwkProperties.ttl(), ChronoUnit.MINUTES);

        return JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .audience(List.of(jwkProperties.audience()))
                .issuer(jwkProperties.issuer())
                .build();
    }

    private JwsHeader buildHeader() {
        return JwsHeader.with(RS256)
                .keyId(jwkProperties.keyId())
                .type("JWT")
                .build();
    }
}
