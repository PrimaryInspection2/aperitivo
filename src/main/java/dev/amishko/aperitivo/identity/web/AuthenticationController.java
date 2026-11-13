package dev.amishko.aperitivo.identity.web;

import dev.amishko.aperitivo.identity.dto.LoginResult;
import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoAccessToken;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshToken;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshTokenResult;
import dev.amishko.aperitivo.identity.dto.tokens.google.GoogleIdToken;
import dev.amishko.aperitivo.identity.service.aperitivo.*;
import dev.amishko.aperitivo.identity.service.auth.GoogleIdTokenVerifier;
import dev.amishko.aperitivo.identity.service.auth.LoginRegistrationService;
import dev.amishko.aperitivo.identity.service.auth.LogoutService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static dev.amishko.aperitivo.identity.web.AuthenticationController.AUTH_API_BASE_PATH;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping(AUTH_API_BASE_PATH)
public class AuthenticationController {

    final static String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    final static String AUTH_API_BASE_PATH = "/api/auth";

    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final LoginRegistrationService loginRegistrationService;
    private final LogoutService logoutService;

    private final AperitivoAccessTokenIssuer aperitivoAccessTokenIssuer;
    private final AperitivoRefreshTokenIssuer aperitivoRefreshTokenIssuer;
    private final AperitivoRefreshTokenRotationService aperitivoRefreshTokenRotationService;

    private final Clock clock;

    @PostMapping(value = "/google", consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<@NonNull AperitivoAccessToken> loginWithGoogle(@RequestBody GoogleIdToken googleIdToken) {
        ProviderIdentityProfile providerIdentityProfile = googleIdTokenVerifier.verifyIdentity(googleIdToken.idToken());
        LoginResult loginResult = loginRegistrationService.handleLogin(providerIdentityProfile);
        AperitivoAccessToken accessToken = aperitivoAccessTokenIssuer.issueAccessToken(loginResult.userId());
        AperitivoRefreshToken aperitivoRefreshToken = aperitivoRefreshTokenIssuer.issueNewFamily(loginResult.userId());

        Duration refreshCookieMaxAge = Duration.between(Instant.now(clock), aperitivoRefreshToken.expiresAt());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(aperitivoRefreshToken.refreshToken(), refreshCookieMaxAge).toString())
                .cacheControl(CacheControl.noStore())
                .body(accessToken);
    }

    @PostMapping(value = "/refresh", consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<@NonNull AperitivoAccessToken> refresh(@CookieValue(value = REFRESH_TOKEN_COOKIE_NAME, required = false) String refreshToken) {
        if (isBlank(refreshToken)) {
            throw new BadCredentialsException("Refresh token is required");
        }

        AperitivoRefreshTokenResult refreshTokenResult = aperitivoRefreshTokenRotationService.rotate(refreshToken);

        UUID userId = refreshTokenResult.userId();
        AperitivoRefreshToken newRefreshToken = refreshTokenResult.token();
        AperitivoAccessToken newAccessToken = aperitivoAccessTokenIssuer.issueAccessToken(userId);

        Duration refreshCookieMaxAge = Duration.between(Instant.now(clock), newRefreshToken.expiresAt());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(newRefreshToken.refreshToken(), refreshCookieMaxAge).toString())
                .cacheControl(CacheControl.noStore())
                .body(newAccessToken);
    }

    @PostMapping(value = "/logout", consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<@NonNull Void> logout(@CookieValue(value = REFRESH_TOKEN_COOKIE_NAME, required = false) String refreshToken) {

        logoutService.logout(refreshToken);

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(null, Duration.ZERO).toString())
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private static ResponseCookie buildRefreshCookie(String refreshToken, Duration maxAge) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, refreshToken)
                .sameSite("Strict")
                .httpOnly(true)
                .path(AUTH_API_BASE_PATH)
                .secure(true)
                .maxAge(maxAge)
                .build();
    }
}
