package dev.amishko.aperitivo.identity.config;

import dev.amishko.aperitivo.identity.config.properties.JWKProperties;
import dev.amishko.aperitivo.identity.security.AccessTokenIssuer;
import dev.amishko.aperitivo.identity.security.exception.ProblemDetailsAccessDeniedHandler;
import dev.amishko.aperitivo.identity.security.exception.ProblemDetailsAuthenticationEntryPoint;
import dev.amishko.aperitivo.identity.security.JwtTokenConverter;
import dev.amishko.aperitivo.identity.web.OAuth2ConfigurationController;
import dev.amishko.aperitivo.identity.web.UserProfileController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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


@SpringBootTest(
        classes = SecurityChainTest.Cfg.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "logging.level.org.springframework.security=WARN",
                "logging.level.org.springframework.web=WARN"
        })
class SecurityChainTest {

    private static final String ISSUER = "https://aperitivo.test";
    private static final String AUDIENCE = "aperitivo-api";
    private static final String ERRORS = "https://aperitivo.app/errors/";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBE = "/api/test-probe";

    private static final KeyPair OUR_KEYS = rsa();
    private static final KeyPair FOREIGN_KEYS = rsa();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JWKProperties ourProperties;

    @Autowired
    private JsonMapper jsonMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Nested
    @DisplayName("anonymous request")
    class Anonymous {

        @Test
        @DisplayName("/api/ without a token → 401 Problem Details + WWW-Authenticate: Bearer")
        void apiWithoutToken() throws Exception {
            HttpResponse<String> response = get(PROBE, null);
            assertUnauthenticated(response, PROBE);
        }

        @Test
        @DisplayName("GET /api/me without a token → 401 Problem Details")
        void meWithoutToken() throws Exception {
            assertUnauthenticated(get("/api/me", null), "/api/me");
        }

        @Test
        @DisplayName("unknown path → 401, indistinguishable from an existing protected path")
        void unknownPath() throws Exception {
            HttpResponse<String> response = get("/foo", null);

            assertUnauthenticated(response, "/foo");
        }

        @Test
        @DisplayName("actuator endpoint outside the allow-list → 401")
        void actuatorOutsideAllowList() throws Exception {
            assertUnauthenticated(get("/actuator/env", null), "/actuator/env");
        }

        @Test
        @DisplayName("/.well-known/jwks.json is public")
        void jwksIsPublic() throws Exception {
            HttpResponse<String> response = get("/.well-known/jwks.json", null);

            assertThat(response.statusCode()).isEqualTo(200);
            assertNoSessionCookie(response);
        }
    }

    @Nested
    @DisplayName("bad bearer token")
    class BadToken {

        @Test
        @DisplayName("expired token → 401 Problem Details; body does not leak the validator message")
        void expiredToken() throws Exception {
            Instant longAgo = Instant.now().minus(Duration.ofHours(1));
            String token = issuer(ourProperties, longAgo).issueAccessToken(UUID.randomUUID()).token();

            HttpResponse<String> response = get(PROBE, token);

            assertUnauthenticated(response, PROBE);
            assertThat(response.body()).doesNotContainIgnoringCase("expired").doesNotContain("Jwt");
        }

        @Test
        @DisplayName("token signed by a foreign key → 401 Problem Details")
        void foreignSignature() throws Exception {
            JWKProperties foreign = properties(FOREIGN_KEYS);
            String token = issuer(foreign, Instant.now()).issueAccessToken(UUID.randomUUID()).token();

            assertUnauthenticated(get(PROBE, token), PROBE);
        }

        @Test
        @DisplayName("correctly signed token whose sub is not a UUID → 401 Problem Details, not 500")
        void subIsNotAUuid() throws Exception {
            assertUnauthenticated(get(PROBE, tokenWithSubject("google-sub-1234567890")), PROBE);
        }

        @Test
        @DisplayName("garbage in the Authorization header → 401 Problem Details")
        void malformedToken() throws Exception {
            assertUnauthenticated(get(PROBE, "not-a-jwt"), PROBE);
        }
    }

    @Nested
    @DisplayName("valid bearer token")
    class ValidToken {

        @Test
        @DisplayName("/api/** → reaches the controller")
        void apiWithValidToken() throws Exception {
            HttpResponse<String> response = get(PROBE, validToken());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("ok");
            assertNoSessionCookie(response);
        }

        @Test
        @DisplayName("GET /api/me → 200 with the userId taken from the token's sub")
        void me() throws Exception {
            UUID userId = UUID.randomUUID();
            String token = issuer(ourProperties, Instant.now()).issueAccessToken(userId).token();

            HttpResponse<String> response = get("/api/me", token);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(userId.toString());
            assertNoSessionCookie(response);
        }

        @Test
        @DisplayName("path outside the allow-list → 403 Problem Details, not 404")
        void deniedPath() throws Exception {
            HttpResponse<String> response = get("/foo", validToken());

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().firstValue("WWW-Authenticate"))
                    .hasValueSatisfying(v -> assertThat(v).startsWith("Bearer").contains("insufficient_scope"));
            assertProblem(response, 403, "forbidden", "/foo");
            assertNoSessionCookie(response);
        }
    }

    private void assertUnauthenticated(HttpResponse<String> response, String path) throws Exception {
        assertThat(response.statusCode()).as("status for %s", path).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("Bearer"));
        assertProblem(response, 401, "unauthenticated", path);
        assertNoSessionCookie(response);
    }

    private void assertProblem(HttpResponse<String> response, int status, String typeSlug, String path)
            throws Exception {
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).startsWith(PROBLEM_JSON));
        JsonNode body = jsonMapper.readTree(response.body());
        assertThat(body.path("type").asString()).isEqualTo(ERRORS + typeSlug);
        assertThat(body.path("title").asString()).isNotBlank();
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("instance").asString()).isEqualTo(path);
    }

    private static void assertNoSessionCookie(HttpResponse<String> response) {
        assertThat(response.headers().allValues("Set-Cookie"))
                .as("stateless chain must never create a session")
                .noneMatch(c -> c.startsWith("JSESSIONID"));
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String tokenWithSubject(String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(RS256).keyId("test-kid").build();
        return new AperitivoJWKConfig(ourProperties).aperitivoJwtEncoder()
                .encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String validToken() {
        return issuer(ourProperties, Instant.now()).issueAccessToken(UUID.randomUUID()).token();
    }

    private static AccessTokenIssuer issuer(JWKProperties properties, Instant issuedAt) {
        return new AccessTokenIssuer(properties, new AperitivoJWKConfig(properties).aperitivoJwtEncoder(),
                Clock.fixed(issuedAt, ZoneOffset.UTC));
    }

    private static JWKProperties properties(KeyPair keys) {
        return new JWKProperties((RSAPublicKey) keys.getPublic(), (RSAPrivateKey) keys.getPrivate(),
                "test-kid", ISSUER, AUDIENCE, 15);
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

    // Deliberately not @Configuration: registered via classes= only, so the full application's
    // component scan (AperitivoApplicationTests) never picks it up. @TestComponent does the same for the probe.
    @ImportAutoConfiguration(classes = {
            TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class
    })
    @Import({
            SecurityConfig.class,
            AperitivoJWKConfig.class,
            ProblemDetailsAuthenticationEntryPoint.class,
            ProblemDetailsAccessDeniedHandler.class,
            JwtTokenConverter.class,
            OAuth2ConfigurationController.class,
            UserProfileController.class,
            ProbeController.class
    })
    static class Cfg {

        @Bean
        JWKProperties jwkProperties() {
            return properties(OUR_KEYS);
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