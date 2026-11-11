package dev.amishko.aperitivo.identity.web;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.identity.dto.IdentityProvider;
import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import dev.amishko.aperitivo.identity.repository.RefreshTokenRepository;
import dev.amishko.aperitivo.identity.service.security.GoogleIdTokenVerifier;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.amishko.aperitivo.identity.util.cipher.TokenHashUtil.hashToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class RefreshTokenEndpointsIT extends BaseIT {

    private static final String COOKIE = "refresh_token";
    private static final String JSON = "application/json";
    private static final long IDLE_SECONDS = Duration.ofDays(14).toSeconds();
    private static final Pattern MAX_AGE = Pattern.compile("(?i)Max-Age=(\\d+)");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Clock clock;

    @MockitoBean
    private GoogleIdTokenVerifier googleIdTokenVerifier;

    @BeforeEach
    void stubGoogle() {
        given(googleIdTokenVerifier.verifyIdentity(anyString())).willReturn(new ProviderIdentityProfile(
                IdentityProvider.GOOGLE, "google-sub-" + UUID.randomUUID(), "a@example.com", "A", null));
    }

    @Nested
    @DisplayName("POST /api/auth/google sets the refresh cookie")
    class LoginCookie {

        @Test
        @DisplayName("Set-Cookie refresh_token: HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age = 14 days")
        void loginSetsRefreshCookie() throws Exception {
            String setCookie = refreshSetCookie(login());

            assertRefreshCookieAttributes(setCookie);
            assertThat(maxAge(setCookie)).isBetween(IDLE_SECONDS - 60, IDLE_SECONDS);
        }

        @Test
        @DisplayName("cookie carries the raw token, the DB only its hash, the body never the refresh token")
        void cookieIsRawTokenDbIsHash() throws Exception {
            MockHttpServletResponse response = login();
            String raw = cookieValue(response);

            assertThat(raw).isNotBlank();
            assertThat(refreshTokenRepository.findById(raw)).isEmpty();
            assertThat(refreshTokenRepository.findById(hashToken(raw))).isPresent();
            assertThat(response.getContentAsString()).doesNotContain(raw).doesNotContain("refreshToken");
        }

        @Test
        @DisplayName("each login starts a new family")
        void eachLoginIsNewFamily() throws Exception {
            assertThat(familyOf(cookieValue(login()))).isNotEqualTo(familyOf(cookieValue(login())));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/refresh")
    class Refresh {

        @Test
        @DisplayName("valid cookie, no Authorization → 200 {accessToken,…}, no-store, rotated cookie; new access token works")
        void refreshRotates() throws Exception {
            MockHttpServletResponse loginResponse = login();
            String r1 = cookieValue(loginResponse);
            String userId = me(accessToken(loginResponse));

            MockHttpServletResponse response = refresh(r1);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            JsonNode body = jsonMapper.readTree(response.getContentAsString());
            assertThat(body.path("tokenType").asString()).isEqualTo("Bearer");
            assertThat(body.path("expiresIn").asLong()).isEqualTo(900L);

            assertRefreshCookieAttributes(refreshSetCookie(response));
            String r2 = cookieValue(response);
            assertThat(r2).isNotEqualTo(r1);
            assertThat(familyOf(r2)).isEqualTo(familyOf(r1));
            assertThat(response.getContentAsString()).doesNotContain(r2);

            assertThat(me(body.path("accessToken").asString())).isEqualTo(userId);
        }

        @Test
        @DisplayName("no cookie → 401 Problem Details")
        void noCookie() throws Exception {
            MockHttpServletResponse response = mvc.perform(post("/api/auth/refresh").contentType(JSON).content("{}"))
                    .andReturn().getResponse();

            assertUnauthenticated(response);
        }

        @Test
        @DisplayName("unknown cookie value → 401 Problem Details")
        void unknownCookie() throws Exception {
            assertUnauthenticated(refresh("not-a-token-we-issued"));
        }

        @Test
        @DisplayName("token of a logged-out family → 401")
        void afterLogout() throws Exception {
            String r1 = cookieValue(login());
            logout(r1);

            assertUnauthenticated(refresh(r1));
        }

        @Test
        @DisplayName("non-JSON request → 415, token not consumed (JSON-only is part of the CSRF defence)")
        void nonJson() throws Exception {
            String r1 = cookieValue(login());

            MockHttpServletResponse response = mvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie(COOKIE, r1))
                            .contentType("application/x-www-form-urlencoded").content("a=b"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(415);
            assertThat(usedAt(r1)).isNull();
        }

        @Test
        @DisplayName("GET is not part of the public surface → not 200")
        void getIsNotPermitted() throws Exception {
            String r1 = cookieValue(login());

            int status = mvc.perform(get("/api/auth/refresh").cookie(new Cookie(COOKIE, r1)))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isIn(401, 405);
        }

        @Test
        @DisplayName("near the absolute limit: cookie Max-Age and expires_at never outlive family_created_at + 30 days")
        void successorCappedByAbsoluteLifetime() throws Exception {
            String r1 = cookieValue(login());
            Instant familyCreatedAt = clock.instant().minus(Duration.ofDays(25));
            jdbc.update("UPDATE identity.refresh_tokens SET family_created_at = ? WHERE family_id = ?",
                    Timestamp.from(familyCreatedAt), familyOf(r1));

            MockHttpServletResponse response = refresh(r1);

            assertThat(response.getStatus()).isEqualTo(200);
            long fiveDays = Duration.ofDays(5).toSeconds();
            assertThat(maxAge(refreshSetCookie(response))).isBetween(fiveDays - 60, fiveDays);
            Instant expiresAt = column(cookieValue(response), "expires_at").toInstant();
            assertThat(expiresAt).isBeforeOrEqualTo(familyCreatedAt.plus(Duration.ofDays(30)).plusSeconds(1));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/logout")
    class Logout {

        @Test
        @DisplayName("valid cookie → 204, cookie cleared with the same attributes, whole family revoked")
        void logoutRevokesFamily() throws Exception {
            String r1 = cookieValue(login());
            String r2 = cookieValue(refresh(r1));
            UUID family = familyOf(r2);

            MockHttpServletResponse response = logout(r2);

            assertThat(response.getStatus()).isEqualTo(204);
            assertCookieCleared(response);
            assertThat(unrevokedInFamily(family)).isZero();
        }

        @Test
        @DisplayName("logout ends one session only: another family of the same user keeps refreshing")
        void logoutLeavesOtherFamilies() throws Exception {
            MockHttpServletResponse firstLogin = login();
            String laptop = cookieValue(firstLogin);
            String phone = issueAnotherFamilyFor(UUID.fromString(me(accessToken(firstLogin))));

            logout(laptop);

            assertThat(refresh(phone).getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("idempotent: no cookie → 204 + cookie cleared")
        void noCookie() throws Exception {
            MockHttpServletResponse response = mvc.perform(post("/api/auth/logout").contentType(JSON).content("{}"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(204);
            assertCookieCleared(response);
        }

        @Test
        @DisplayName("idempotent: unknown cookie → 204 + cookie cleared")
        void unknownCookie() throws Exception {
            MockHttpServletResponse response = logout("not-a-token-we-issued");

            assertThat(response.getStatus()).isEqualTo(204);
            assertCookieCleared(response);
        }

        @Test
        @DisplayName("idempotent: second logout with the same cookie → 204 + cookie cleared")
        void twice() throws Exception {
            String r1 = cookieValue(login());
            logout(r1);

            MockHttpServletResponse response = logout(r1);

            assertThat(response.getStatus()).isEqualTo(204);
            assertCookieCleared(response);
        }

        @Test
        @DisplayName("logout with an already-rotated token still revokes the family")
        void logoutWithUsedToken() throws Exception {
            String r1 = cookieValue(login());
            String r2 = cookieValue(refresh(r1));

            assertThat(logout(r1).getStatus()).isEqualTo(204);
            assertThat(unrevokedInFamily(familyOf(r2))).isZero();
        }

        @Test
        @DisplayName("non-JSON request → 415, nothing revoked")
        void nonJson() throws Exception {
            String r1 = cookieValue(login());

            MockHttpServletResponse response = mvc.perform(post("/api/auth/logout")
                            .cookie(new Cookie(COOKIE, r1))
                            .contentType("application/x-www-form-urlencoded").content("a=b"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(415);
            assertThat(unrevokedInFamily(familyOf(r1))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("cleanup of expired rows")
    class Cleanup {

        @Test
        @DisplayName("deletes rows past expires_at; keeps live rows, incl. used ones still needed for reuse detection")
        void deletesOnlyExpiredRows() throws Exception {
            String expired = cookieValue(login());
            String usedButLive = cookieValue(login());
            String live = cookieValue(refresh(usedButLive));
            Instant now = clock.instant();
            jdbc.update("UPDATE identity.refresh_tokens SET expires_at = ? WHERE token_hash = ?",
                    Timestamp.from(now.minusSeconds(1)), hashToken(expired));
            // An old family is not by itself a reason to delete; expires_at decides (variant A).
            jdbc.update("UPDATE identity.refresh_tokens SET family_created_at = ? WHERE family_id = ?",
                    Timestamp.from(now.minus(Duration.ofDays(29))), familyOf(live));

            tx.executeWithoutResult(s -> refreshTokenRepository.deleteAllExpiredTokens(now));

            assertThat(refreshTokenRepository.findById(hashToken(expired))).isEmpty();
            assertThat(refreshTokenRepository.findById(hashToken(usedButLive))).isPresent();
            assertThat(refreshTokenRepository.findById(hashToken(live))).isPresent();
        }
    }

    private MockHttpServletResponse login() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/auth/google")
                        .contentType(JSON).content("{\"idToken\":\"google-id-token\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("login").isEqualTo(200);
        return response;
    }

    private MockHttpServletResponse refresh(String rawToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(COOKIE, rawToken))
                        .contentType(JSON).content("{}"))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse logout(String rawToken) throws Exception {
        return mvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie(COOKIE, rawToken))
                        .contentType(JSON).content("{}"))
                .andReturn().getResponse();
    }

    private String me(String accessToken) throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("/api/me").isEqualTo(200);
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        String userId = body.path("userId").asString(body.path("userUuid").asString());
        assertThat(userId).isNotBlank();
        return userId;
    }

    private String issueAnotherFamilyFor(UUID userId) {
        String raw = "second-device-" + UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                        INSERT INTO identity.refresh_tokens
                            (token_hash, user_id, family_id, family_created_at, expires_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)""",
                hashToken(raw), userId, UUID.randomUUID(),
                Timestamp.from(now), Timestamp.from(now.plus(Duration.ofDays(14))), Timestamp.from(now));
        return raw;
    }

    private String accessToken(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString()).path("accessToken").asString();
    }

    private static String refreshSetCookie(MockHttpServletResponse response) {
        List<String> refreshCookies = response.getHeaders("Set-Cookie").stream()
                .filter(c -> c.startsWith(COOKIE + "="))
                .toList();
        assertThat(refreshCookies).as("exactly one refresh_token Set-Cookie").hasSize(1);
        return refreshCookies.getFirst();
    }

    private static String cookieValue(MockHttpServletResponse response) {
        String setCookie = refreshSetCookie(response);
        return setCookie.substring((COOKIE + "=").length(), setCookie.indexOf(';'));
    }

    private static void assertRefreshCookieAttributes(String setCookie) {
        assertThat(setCookie)
                .containsIgnoringCase("; HttpOnly")
                .containsIgnoringCase("; Secure")
                .containsIgnoringCase("; SameSite=Strict")
                .containsIgnoringCase("; Path=/api/auth");
        assertThat(setCookie).doesNotContainIgnoringCase("Domain=");
    }

    private static void assertCookieCleared(MockHttpServletResponse response) {
        String setCookie = refreshSetCookie(response);
        assertThat(setCookie).startsWith(COOKIE + "=;");
        assertRefreshCookieAttributes(setCookie);
        assertThat(maxAge(setCookie)).isZero();
    }

    private static long maxAge(String setCookie) {
        Matcher m = MAX_AGE.matcher(setCookie);
        assertThat(m.find()).as("Max-Age present").isTrue();
        return Long.parseLong(m.group(1));
    }

    private static void assertUnauthenticated(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }

    private Timestamp usedAt(String rawToken) {
        return column(rawToken, "used_at");
    }

    private Timestamp column(String rawToken, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM identity.refresh_tokens WHERE token_hash = ?",
                Timestamp.class, hashToken(rawToken));
    }

    private UUID familyOf(String rawToken) {
        return jdbc.queryForObject("SELECT family_id FROM identity.refresh_tokens WHERE token_hash = ?",
                UUID.class, hashToken(rawToken));
    }

    private int unrevokedInFamily(UUID familyId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM identity.refresh_tokens WHERE family_id = ? AND revoked_at IS NULL",
                Integer.class, familyId);
    }
}
