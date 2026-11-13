package dev.amishko.aperitivo.identity.config;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.identity.config.properties.JWKProperties;
import dev.amishko.aperitivo.identity.service.aperitivo.AperitivoAccessTokenIssuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The single stateless {@code SecurityFilterChain} ({@code api.md} §Security filter chain, ADR 0012):
 * deny by default, only OUR access tokens are bearer tokens, 401/403 as Problem Details, no HTTP session.
 * Endpoint contracts of {@code /api/auth/*} live in {@code AuthEndpointsIT}.
 */
@AutoConfigureMockMvc
@Import(SecurityChainIT.ProbeController.class)
class SecurityChainIT extends BaseIT {

    private static final String ERRORS = "https://aperitivo.app/errors/";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBE = "/api/test-probe";

    private static final KeyPair FOREIGN_KEYS = rsa();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JWKProperties ourProperties;

    @Autowired
    private AperitivoAccessTokenIssuer accessTokenIssuer;

    @Nested
    @DisplayName("anonymous request")
    class Anonymous {

        @Test
        @DisplayName("/api/ without a token → 401 Problem Details + WWW-Authenticate: Bearer")
        void apiWithoutToken() throws Exception {
            assertUnauthenticated(perform(get(PROBE)), PROBE);
        }

        @Test
        @DisplayName("GET /api/me without a token → 401 Problem Details")
        void meWithoutToken() throws Exception {
            assertUnauthenticated(perform(get("/api/me")), "/api/me");
        }

        @Test
        @DisplayName("unknown path → 401, indistinguishable from an existing protected path")
        void unknownPath() throws Exception {
            assertUnauthenticated(perform(get("/foo")), "/foo");
        }

        @Test
        @DisplayName("actuator endpoint outside the allow-list → 401")
        void actuatorOutsideAllowList() throws Exception {
            assertUnauthenticated(perform(get("/actuator/env")), "/actuator/env");
        }

        @Test
        @DisplayName("/.well-known/jwks.json is public")
        void jwksIsPublic() throws Exception {
            MvcResult result = perform(get("/.well-known/jwks.json"));

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertNoSession(result);
        }
    }

    @Nested
    @DisplayName("bad bearer token")
    class BadToken {

        @Test
        @DisplayName("expired token → 401 Problem Details; body does not leak the validator message")
        void expiredToken() throws Exception {
            String token = issuer(ourProperties, Instant.now().minus(Duration.ofHours(1)))
                    .issueAccessToken(UUID.randomUUID()).accessToken();

            MvcResult result = perform(bearer(get(PROBE), token));

            assertUnauthenticated(result, PROBE);
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContainIgnoringCase("expired").doesNotContain("Jwt");
        }

        @Test
        @DisplayName("token signed by a foreign key (even with our kid) → 401 Problem Details")
        void foreignSignature() throws Exception {
            String token = issuer(foreignProperties(), Instant.now()).issueAccessToken(UUID.randomUUID()).accessToken();

            assertUnauthenticated(perform(bearer(get(PROBE), token)), PROBE);
        }

        @Test
        @DisplayName("correctly signed token whose sub is not a UUID → 401 Problem Details, not 500")
        void subIsNotAUuid() throws Exception {
            assertUnauthenticated(perform(bearer(get(PROBE), tokenWithSubject("google-sub-1234567890"))), PROBE);
        }

        @Test
        @DisplayName("Google-shaped ID token as bearer on /api/** → 401 (only OUR tokens are bearer tokens)")
        void googleIdTokenAsBearer() throws Exception {
            assertUnauthenticated(perform(bearer(get("/api/me"), googleShapedToken())), "/api/me");
        }

        @Test
        @DisplayName("garbage in the Authorization header → 401 Problem Details")
        void malformedToken() throws Exception {
            assertUnauthenticated(perform(bearer(get(PROBE), "not-a-jwt")), PROBE);
        }
    }

    @Nested
    @DisplayName("valid bearer token")
    class ValidToken {

        @Test
        @DisplayName("/api/** → reaches the controller")
        void apiWithValidToken() throws Exception {
            MvcResult result = perform(bearer(get(PROBE), validToken(UUID.randomUUID())));

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString()).isEqualTo("ok");
            assertNoSession(result);
        }

        @Test
        @DisplayName("GET /api/me → 200 with the userId taken from the token's sub")
        void me() throws Exception {
            UUID userId = UUID.randomUUID();

            MvcResult result = perform(bearer(get("/api/me"), validToken(userId)));

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString()).contains(userId.toString());
            assertNoSession(result);
        }

        @Test
        @DisplayName("path outside the allow-list → 403 Problem Details, not 404")
        void deniedPath() throws Exception {
            MvcResult result = perform(bearer(get("/foo"), validToken(UUID.randomUUID())));

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getResponse().getHeader("WWW-Authenticate"))
                    .startsWith("Bearer").contains("insufficient_scope");
            assertProblem(result.getResponse(), 403, "forbidden", "/foo");
            assertNoSession(result);
        }
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private void assertUnauthenticated(MvcResult result, String path) throws Exception {
        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).as("status for %s", path).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertProblem(response, 401, "unauthenticated", path);
        assertNoSession(result);
    }

    private void assertProblem(MockHttpServletResponse response, int status, String typeSlug, String path)
            throws Exception {
        assertThat(response.getContentType()).startsWith(PROBLEM_JSON);
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.path("type").asString()).isEqualTo(ERRORS + typeSlug);
        assertThat(body.path("title").asString()).isNotBlank();
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("instance").asString()).isEqualTo(path);
    }

    // MockMvc never emits Set-Cookie: JSESSIONID, so assert the cause instead: no session was created.
    private static void assertNoSession(MvcResult result) {
        assertThat(result.getRequest().getSession(false)).as("stateless chain must never create a session").isNull();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("JSESSIONID"));
    }

    private String validToken(UUID userId) {
        return accessTokenIssuer.issueAccessToken(userId).accessToken();
    }

    private String tokenWithSubject(String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuer(ourProperties.issuer())
                .audience(List.of(ourProperties.audience()))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .id(UUID.randomUUID().toString())
                .build();
        return sign(ourProperties, claims);
    }

    /**
     * Claims shaped like a Google ID token, signed by a key that is not ours (with a colliding kid).
     */
    private String googleShapedToken() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://accounts.google.com")
                .subject("108234567890123456789")
                .audience(List.of("aperitivo-client.apps.googleusercontent.com"))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1)))
                .claim("email", "a@example.com")
                .claim("email_verified", true)
                .build();
        return sign(foreignProperties(), claims);
    }

    private static String sign(JWKProperties properties, JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(RS256).keyId(properties.keyId()).build();
        return new AperitivoJWKConfig(properties).aperitivoJwtEncoder()
                .encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static AperitivoAccessTokenIssuer issuer(JWKProperties properties, Instant issuedAt) {
        return new AperitivoAccessTokenIssuer(properties, new AperitivoJWKConfig(properties).aperitivoJwtEncoder(),
                Clock.fixed(issuedAt, ZoneOffset.UTC));
    }

    private JWKProperties foreignProperties() {
        return new JWKProperties((RSAPublicKey) FOREIGN_KEYS.getPublic(), (RSAPrivateKey) FOREIGN_KEYS.getPrivate(),
                ourProperties.keyId(), ourProperties.issuer(), ourProperties.audience(), ourProperties.ttl());
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestComponent
    @RestController
    static class ProbeController {

        @GetMapping(PROBE)
        String probe() {
            return "ok";
        }
    }
}