package dev.amishko.aperitivo.identity.security;

import dev.amishko.aperitivo.identity.config.AperitivoJWKConfig;
import dev.amishko.aperitivo.identity.config.properties.JWKProperties;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoAccessToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokenIssuerTest {

    private static final String KEY_ID = "test-kid";
    private static final String ISSUER = "https://aperitivo.test";
    private static final String AUDIENCE = "aperitivo-api";
    private static final int TTL_MINUTES = 15;

    private static KeyPair ourKeys;
    private static KeyPair foreignKeys;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        ourKeys = generator.generateKeyPair();
        foreignKeys = generator.generateKeyPair();
    }

    private static JWKProperties properties(KeyPair keys, String issuer, String audience) {
        return new JWKProperties((RSAPublicKey) keys.getPublic(), (RSAPrivateKey) keys.getPrivate(),
                KEY_ID, issuer, audience, TTL_MINUTES);
    }

    private static AccessTokenIssuer issuer(JWKProperties properties, Clock clock) {
        return new AccessTokenIssuer(properties, new AperitivoJWKConfig(properties).aperitivoJwtEncoder(), clock);
    }

    private static Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private final JWKProperties ourProperties = properties(ourKeys, ISSUER, AUDIENCE);
    private final JwtDecoder decoder = new AperitivoJWKConfig(ourProperties).aperitivoJwtDecoder();

    @Test
    @DisplayName("round-trip: issued token decodes with claims and kid")
    void roundTrip() {
        UUID userId = UUID.randomUUID();
        Instant issuedAt = now();

        AperitivoAccessToken dto = issuer(ourProperties, fixedAt(issuedAt)).issueAccessToken(userId);
        Jwt jwt = decoder.decode(dto.accessToken());

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
        assertThat(jwt.getIssuedAt()).isEqualTo(issuedAt);
        assertThat(jwt.getExpiresAt()).isEqualTo(issuedAt.plus(Duration.ofMinutes(TTL_MINUTES)));
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getClaims()).containsOnlyKeys("sub", "iss", "aud", "iat", "exp", "jti");
        assertThat(jwt.getHeaders()).containsEntry("kid", KEY_ID).containsEntry("alg", "RS256");
        assertThat(dto.tokenType()).isEqualTo("Bearer");
        assertThat(dto.expiresIn())
                .as("expiresIn is seconds until exp (RFC 6749 §5.1, api.md): 15 min = 900")
                .isEqualTo(TTL_MINUTES * 60L);
    }

    @Test
    @DisplayName("each token gets a unique jti")
    void uniqueJti() {
        AccessTokenIssuer issuer = issuer(ourProperties, fixedAt(now()));
        UUID userId = UUID.randomUUID();

        String first = decoder.decode(issuer.issueAccessToken(userId).accessToken()).getId();
        String second = decoder.decode(issuer.issueAccessToken(userId).accessToken()).getId();

        assertThat(first).isNotEqualTo(second);
    }

    @Nested
    @DisplayName("decoder rejects")
    class Rejects {

        @Test
        void tokenSignedWithForeignKey() {
            String token = issuer(properties(foreignKeys, ISSUER, AUDIENCE), fixedAt(now()))
                    .issueAccessToken(UUID.randomUUID()).accessToken();

            assertThatThrownBy(() -> decoder.decode(token))
                    .isInstanceOf(BadJwtException.class)
                    .isNotInstanceOf(JwtValidationException.class);
        }

        @Test
        void expiredToken() {
            Instant longAgo = now().minus(Duration.ofHours(1));
            String token = issuer(ourProperties, fixedAt(longAgo)).issueAccessToken(UUID.randomUUID()).accessToken();

            assertThatThrownBy(() -> decoder.decode(token))
                    .isInstanceOf(JwtValidationException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        void foreignAudience() {
            String token = issuer(properties(ourKeys, ISSUER, "aperitivo-admin"), fixedAt(now()))
                    .issueAccessToken(UUID.randomUUID()).accessToken();

            assertThatThrownBy(() -> decoder.decode(token))
                    .isInstanceOf(JwtValidationException.class)
                    .hasMessageContaining("aud");
        }

        @Test
        void foreignIssuer() {
            String token = issuer(properties(ourKeys, "https://accounts.google.com", AUDIENCE), fixedAt(now()))
                    .issueAccessToken(UUID.randomUUID()).accessToken();

            assertThatThrownBy(() -> decoder.decode(token))
                    .isInstanceOf(JwtValidationException.class)
                    .hasMessageContaining("iss");
        }

        @Test
        void tamperedPayload() {
            String token = issuer(ourProperties, fixedAt(now())).issueAccessToken(UUID.randomUUID()).accessToken();
            String[] parts = token.split("\\.");
            Base64.Decoder b64Decoder = Base64.getUrlDecoder();
            Base64.Encoder b64Encoder = Base64.getUrlEncoder().withoutPadding();

            String payload = new String(b64Decoder.decode(parts[1]), StandardCharsets.UTF_8);
            String forgedPayload = payload.replaceFirst("\"sub\":\"[^\"]+\"",
                    "\"sub\":\"" + UUID.randomUUID() + "\"");
            assertThat(forgedPayload).isNotEqualTo(payload);

            String forged = parts[0] + "." + b64Encoder.encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
                    + "." + parts[2];

            assertThatThrownBy(() -> decoder.decode(forged))
                    .isInstanceOf(BadJwtException.class)
                    .isNotInstanceOf(JwtValidationException.class);
        }
    }
}