package dev.amishko.aperitivo.identity.web;

import dev.amishko.aperitivo.identity.dto.LoginResult;
import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoAccessToken;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshToken;
import dev.amishko.aperitivo.identity.dto.tokens.google.GoogleIdToken;
import dev.amishko.aperitivo.identity.service.LoginRegistrationService;
import dev.amishko.aperitivo.identity.service.security.AperitivoAccessTokenIssuer;
import dev.amishko.aperitivo.identity.service.security.AperitivoRefreshTokenIssuer;
import dev.amishko.aperitivo.identity.service.security.GoogleIdTokenVerifier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

import static dev.amishko.aperitivo.identity.util.web.RefreshTokenCookieUtil.setRefreshCookie;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Validated
@RequiredArgsConstructor
@RestController
public class AuthenticationController {

    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final LoginRegistrationService loginRegistrationService;
    private final AperitivoAccessTokenIssuer aperitivoAccessTokenIssuer;
    private final AperitivoRefreshTokenIssuer aperitivoRefreshTokenIssuer;
    private final Clock clock;

    @PostMapping(value = "/api/auth/google", consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<@NonNull AperitivoAccessToken> loginWithGoogle(@RequestBody GoogleIdToken googleIdToken) {
        ProviderIdentityProfile providerIdentityProfile = googleIdTokenVerifier.verifyIdentity(googleIdToken.idToken());
        LoginResult loginResult = loginRegistrationService.handleLogin(providerIdentityProfile);
        AperitivoAccessToken accessToken = aperitivoAccessTokenIssuer.issueAccessToken(loginResult.userId());
        AperitivoRefreshToken aperitivoRefreshToken = aperitivoRefreshTokenIssuer.issueNewFamily(loginResult.userId());

        return  ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, setRefreshCookie(aperitivoRefreshToken, Instant.now(clock)).toString())
                .cacheControl(CacheControl.noStore())
                .body(accessToken);
    }
}
