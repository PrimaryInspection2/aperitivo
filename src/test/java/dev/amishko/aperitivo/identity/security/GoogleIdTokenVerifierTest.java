package dev.amishko.aperitivo.identity.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import dev.amishko.aperitivo.identity.dto.IdentityProvider;
import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the Google ID-token checks (ADR 0012 §2, plan step 4) against a fake Google issuer: a local
 * HTTP server serves OIDC discovery and a JWKS, so signature, iss, aud, exp and email_verified are all
 * exercised through the real {@code NimbusJwtDecoder} + validators, without touching accounts.google.com.
 */
class GoogleIdTokenVerifierTest {

    private static final String CLIENT_ID = "aperitivo-test-client.apps.googleusercontent.com";

    private static HttpServer fakeGoogle;
    private static String issuer;
    private static RSAKey googleKey;
    private static RSAKey foreignKey;
    private static GoogleIdTokenVerifier verifier;

    @BeforeAll
    static void startFakeGoogle() throws Exception {
        googleKey = new RSAKeyGenerator(2048).keyID("google-kid").generate();
        foreignKey = new RSAKeyGenerator(2048).keyID("google-kid").generate();

        fakeGoogle = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        issuer = "http://localhost:" + fakeGoogle.getAddress().getPort();
        respond("/.well-known/openid-configuration", """
                {"issuer":"%1$s","jwks_uri":"%1$s/certs","authorization_endpoint":"%1$s/auth",
                 "token_endpoint":"%1$s/token","subject_types_supported":["public"],
                 "id_token_signing_alg_values_supported":["RS256"],"response_types_supported":["code"]}
                """.formatted(issuer));
        respond("/certs", new JWKSet(googleKey.toPublicJWK()).toString());
        fakeGoogle.start();

        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId(CLIENT_ID)
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/unused")
                .scope("openid", "email", "profile")
                .authorizationUri(issuer + "/auth")
                .tokenUri(issuer + "/token")
                .jwkSetUri(issuer + "/certs")
                .issuerUri(issuer)
                .build();
        verifier = new GoogleIdTokenVerifier(new InMemoryClientRegistrationRepository(google));
    }

    @AfterAll
    static void stopFakeGoogle() {
        fakeGoogle.stop(0);
    }

    @Test
    @DisplayName("valid ID token → profile mapped from sub, email, name, picture")
    void validToken() throws Exception {
        ProviderIdentityProfile profile = verifier.verifyIdentity(idToken(googleKey, claims -> { }));

        assertThat(profile.provider()).isEqualTo(IdentityProvider.GOOGLE);
        assertThat(profile.providerUserId()).isEqualTo("google-sub-42");
        assertThat(profile.email()).isEqualTo("andrii@example.com");
        assertThat(profile.displayName()).isEqualTo("Andrii K.");
        assertThat(profile.avatarUrl()).isEqualTo("https://example.com/a.png");
    }

    @Test
    @DisplayName("token issued to another app (aud) → rejected: token substitution")
    void foreignAudience() throws Exception {
        assertRejected(idToken(googleKey, c -> c.audience("evil-app.apps.googleusercontent.com")));
    }

    @Test
    @DisplayName("expired token → rejected")
    void expired() throws Exception {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        assertRejected(idToken(googleKey, c -> c.issueTime(Date.from(past))
                .expirationTime(Date.from(past.plus(Duration.ofHours(1))))));
    }

    @Test
    @DisplayName("email_verified = false → rejected")
    void emailNotVerified() throws Exception {
        assertRejected(idToken(googleKey, c -> c.claim("email_verified", false)));
    }

    @Test
    @DisplayName("email_verified missing → rejected")
    void emailVerifiedMissing() throws Exception {
        assertRejected(idToken(googleKey, c -> c.claim("email_verified", null)));
    }

    @Test
    @DisplayName("wrong issuer → rejected")
    void wrongIssuer() throws Exception {
        assertRejected(idToken(googleKey, c -> c.issuer("https://evil.example.com")));
    }

    @Test
    @DisplayName("signed by a key Google does not publish → rejected")
    void foreignSignature() throws Exception {
        assertRejected(idToken(foreignKey, c -> { }));
    }

    @Test
    @DisplayName("blank or garbage input → rejected")
    void garbage() {
        assertRejected("");
        assertRejected("not-a-jwt");
    }

    private static void assertRejected(String idToken) {
        assertThatThrownBy(() -> verifier.verifyIdentity(idToken))
                .as("rejection must be an AuthenticationException so the entry point answers 401")
                .isInstanceOf(BadCredentialsException.class);
    }

    private static String idToken(RSAKey key, Consumer<JWTClaimsSet.Builder> customize) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("google-sub-42")
                .audience(CLIENT_ID)
                .claim("azp", CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .claim("email", "andrii@example.com")
                .claim("email_verified", true)
                .claim("name", "Andrii K.")
                .claim("picture", "https://example.com/a.png");
        customize.accept(claims);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static void respond(String path, String json) {
        fakeGoogle.createContext(path, exchange -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }
}
