package dev.amishko.aperitivo.identity.web;

import dev.amishko.aperitivo.identity.dto.LoginResult;
import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoAccessToken;
import dev.amishko.aperitivo.identity.dto.tokens.google.GoogleIdToken;
import dev.amishko.aperitivo.identity.security.AccessTokenIssuer;
import dev.amishko.aperitivo.identity.security.GoogleIdTokenVerifier;
import dev.amishko.aperitivo.identity.service.LoginRegistrationService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Validated
@RequiredArgsConstructor
@RestController
public class AuthenticationController {

    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final LoginRegistrationService loginRegistrationService;
    private final AccessTokenIssuer accessTokenIssuer;

    @PostMapping(value = "/api/auth/google", consumes = APPLICATION_JSON_VALUE)
    public ResponseEntity<@NonNull AperitivoAccessToken> loginWithGoogle(@RequestBody GoogleIdToken googleIdToken) {
        ProviderIdentityProfile providerIdentityProfile = googleIdTokenVerifier.verifyIdentity(googleIdToken.idToken());
        LoginResult loginResult = loginRegistrationService.handleLogin(providerIdentityProfile);
        AperitivoAccessToken accessToken = accessTokenIssuer.issueAccessToken(loginResult.userId());
        return new ResponseEntity<>(accessToken, createHeaders(), HttpStatus.OK);
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CACHE_CONTROL, "no-cache, no-store");
        return headers;
    }
}
