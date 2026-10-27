package dev.amishko.aperitivo.identity.web;

import dev.amishko.aperitivo.identity.dto.LoginResult;
import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import dev.amishko.aperitivo.identity.dto.tokens.AccessTokenDto;
import dev.amishko.aperitivo.identity.security.AccessTokenIssuer;
import dev.amishko.aperitivo.identity.security.GoogleIdTokenVerifier;
import dev.amishko.aperitivo.identity.service.LoginRegistrationService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RequiredArgsConstructor
@RestController
public class AuthenticationController {

    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final LoginRegistrationService loginRegistrationService;
    private final AccessTokenIssuer accessTokenIssuer;

    @PostMapping("/api/auth/google")
    public ResponseEntity<@NonNull AccessTokenDto> loginWithGoogle(@RequestBody String googleIdToken) {
        ProviderIdentityProfile providerIdentityProfile = googleIdTokenVerifier.verifyIdentity(googleIdToken);
        LoginResult loginResult = loginRegistrationService.handleLogin(providerIdentityProfile);
        AccessTokenDto accessToken = accessTokenIssuer.issueAccessToken(loginResult.userId());
        return new ResponseEntity<>(accessToken, HttpStatus.OK);

    }
}
