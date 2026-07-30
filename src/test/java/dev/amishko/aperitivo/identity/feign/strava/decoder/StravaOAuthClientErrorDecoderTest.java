package dev.amishko.aperitivo.identity.feign.strava.decoder;

import dev.amishko.aperitivo.identity.exception.oauth.OAuthBadRequestException;
import dev.amishko.aperitivo.identity.exception.oauth.OAuthClientCredentialsException;
import dev.amishko.aperitivo.identity.exception.oauth.OAuthTransientException;
import dev.amishko.aperitivo.identity.exception.oauth.OAuthUnexpectedStatusException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StravaOAuthClientErrorDecoder}.
 *
 * <p>Design of the decoder is deliberately status-only ({@code HttpStatus}), so
 * these tests verify the exact status→exception mapping and body-reading resilience.
 * We do <b>not</b> parse the Strava error body — that decision is documented in
 * {@code docs/plans/identity.md} (C.3 caller-side handling).
 */
class StravaOAuthClientErrorDecoderTest {

    private final StravaOAuthClientErrorDecoder decoder = new StravaOAuthClientErrorDecoder();

    // --- status → exception mapping -----------------------------------------

    @Test
    @DisplayName("400 → OAuthBadRequestException (refresh token rejected / invalid_grant)")
    void badRequestMapsToOAuthBadRequestException() {
        Exception ex = decoder.decode("refreshAccessToken", responseWithStatus(400, "{\"error\":\"invalid_grant\"}"));

        assertThat(ex).isInstanceOf(OAuthBadRequestException.class);
    }

    @Test
    @DisplayName("401 → OAuthClientCredentialsException (systemic — our client_secret broken)")
    void unauthorizedMapsToClientCredentialsException() {
        Exception ex = decoder.decode("refreshAccessToken", responseWithStatus(401, "Unauthorized"));

        assertThat(ex)
                .as("401 on /oauth/token means OUR credentials are broken — must NOT be a per-user revoke trigger")
                .isInstanceOf(OAuthClientCredentialsException.class);
    }

    @ParameterizedTest(name = "{0} → OAuthTransientException")
    @ValueSource(ints = {429, 500, 502, 503, 504})
    @DisplayName("transient statuses map to OAuthTransientException")
    void transientStatusesMapToOAuthTransientException(int status) {
        Exception ex = decoder.decode("refreshAccessToken", responseWithStatus(status, "gateway down"));

        assertThat(ex).isInstanceOf(OAuthTransientException.class);
        assertThat(ex).hasMessageContaining(String.valueOf(status));
    }

    @ParameterizedTest(name = "{0} → OAuthUnexpectedStatusException")
    @ValueSource(ints = {403, 404, 405, 409, 418, 522})
    @DisplayName("statuses outside the known set map to OAuthUnexpectedStatusException")
    void unknownStatusesMapToOAuthUnexpectedStatusException(int status) {
        Exception ex = decoder.decode("refreshAccessToken", responseWithStatus(status, ""));

        assertThat(ex).isInstanceOf(OAuthUnexpectedStatusException.class);
        assertThat(ex).hasMessageContaining(String.valueOf(status));
    }

    // --- body reading -------------------------------------------------------

    @Test
    @DisplayName("body is included in transient exception message for diagnostics")
    void transientExceptionMessageContainsBody() {
        String body = "Bad Gateway from upstream";

        Exception ex = decoder.decode("refreshAccessToken", responseWithStatus(502, body));

        assertThat(ex).hasMessageContaining(body);
    }

    @Test
    @DisplayName("missing body — no NPE, decoder returns a mapped exception anyway")
    void nullBodyIsHandledGracefully() {
        Response response = Response.builder()
                .status(502)
                .reason("Bad Gateway")
                .request(request())
                .headers(Map.of())
                .body((Response.Body) null)
                .build();

        Exception ex = decoder.decode("refreshAccessToken", response);

        assertThat(ex).isInstanceOf(OAuthTransientException.class);
    }

    @Test
    @DisplayName("unreadable body — decoder degrades gracefully, still returns mapped exception")
    void unreadableBodyDoesNotBreakDecoder() {
        Response.Body brokenBody = new Response.Body() {
            @Override public Integer length() { return null; }
            @Override public boolean isRepeatable() { return false; }
            @Override public InputStream asInputStream() throws IOException {
                throw new IOException("stream broken");
            }
            @Override public java.io.Reader asReader(java.nio.charset.Charset charset) throws IOException {
                throw new IOException("reader broken");
            }
            @Override public void close() { }
        };
        Response response = Response.builder()
                .status(500)
                .reason("Internal Server Error")
                .request(request())
                .headers(Map.of())
                .body(brokenBody)
                .build();

        Exception ex = decoder.decode("refreshAccessToken", response);

        assertThat(ex).isInstanceOf(OAuthTransientException.class);
    }

    // --- helpers ------------------------------------------------------------

    private static Response responseWithStatus(int status, String body) {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return Response.builder()
                .status(status)
                .reason("test")
                .request(request())
                .headers(Map.of())
                .body(new Response.Body() {
                    @Override public Integer length() { return bytes.length; }
                    @Override public boolean isRepeatable() { return true; }
                    @Override public InputStream asInputStream() { return new ByteArrayInputStream(bytes); }
                    @Override public java.io.Reader asReader(java.nio.charset.Charset charset) {
                        return new java.io.StringReader(new String(bytes, charset));
                    }
                    @Override public void close() { }
                })
                .build();
    }

    private static Request request() {
        return Request.create(
                Request.HttpMethod.POST,
                "https://www.strava.com/oauth/token",
                Map.of(),
                new byte[0],
                StandardCharsets.UTF_8,
                new RequestTemplate());
    }
}
