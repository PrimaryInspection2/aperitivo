package dev.amishko.aperitivo.identity.config;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = FallbackChainScratchTest.Cfg.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "logging.level.org.springframework.security=WARN",
                "logging.level.org.springframework.web=WARN"
        })
class FallbackChainScratchTest {

    private static final List<String> PERMITTED = List.of(
            "/.well-known/jwks.json",
            "/.well-known/openid-configuration",
            "/.well-known/",
            "/.well-known",
            "/swagger-ui.html",
            "/swagger-ui",
            "/swagger-ui/index.html",
            "/swagger-ui/swagger-ui.css",
            "/v3/api-docs",
            "/v3/api-docs/swagger-config",
            "/v3/api-docs.yaml",
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/prometheus");

    private static final List<String> DENIED = List.of(
            "/",
            "/login",
            "/error",
            "/foo",
            "/actuator",
            "/actuator/env",
            "/actuator/heapdump",
            "/actuator/prometheusX",
            "/actuator/prometheus/x",
            "/actuator/healthz",
            "/swagger-uievil",
            "/swagger-ui-evil/x",
            "/v3/api-docsX",
            "/v3/api-docs-x",
            "/ACTUATOR/prometheus",
            "/.well-known-evil/x",
            "/connect/oauth2/code/strava",
            "/login/oauth2/code/google");

    @Configuration
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
    @Import(SecurityConfig.class)
    static class Cfg {
        @Bean
        JwtDecoder jwtDecoder() {
            return Mockito.mock(JwtDecoder.class);
        }
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void probe() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        List<String> mismatches = new ArrayList<>();

        for (String path : PERMITTED) {
            check(client, "GET", path, 404, mismatches);
        }
        check(client, "HEAD", "/actuator/health", 404, mismatches);
        for (String path : DENIED) {
            check(client, "GET", path, 403, mismatches);
        }
        check(client, "GET", "/api/x", 401, mismatches);
        System.out.println("SCRATCH mismatches=" + mismatches.size());

        assertThat(mismatches).isEmpty();
    }

    private void check(HttpClient client, String method, String path, int expected, List<String> mismatches)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String line = "SCRATCH %-4s %-40s -> %d (expected %d)%s".formatted(
                method, path, response.statusCode(), expected,
                response.statusCode() == expected ? "" : "   <== MISMATCH");
        System.out.println(line);
        if (response.statusCode() != expected) {
            mismatches.add(line);
        }
    }
}
