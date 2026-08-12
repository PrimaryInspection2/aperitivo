package dev.amishko.aperitivo.identity.security;

import dev.amishko.aperitivo.identity.dto.IdentityProvider;
import dev.amishko.aperitivo.identity.dto.LoginResult;
import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import dev.amishko.aperitivo.identity.service.OAuth2GoogleLoginCallbackService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
@RequiredArgsConstructor
public class OAuth2GoogleLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final OAuth2GoogleLoginCallbackService oAuth2GoogleLoginCallbackService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws ServletException, IOException {
        OAuth2AuthenticationToken authenticationToken = (OAuth2AuthenticationToken) authentication;

        ProviderIdentityProfile profile = requireProfile(authenticationToken);

        LoginResult loginResult = oAuth2GoogleLoginCallbackService.handleLogin(profile);

        //TODO issue JWT HERE
        super.onAuthenticationSuccess(request, response, authentication);
    }

    private ProviderIdentityProfile requireProfile(OAuth2AuthenticationToken authenticationToken) {
        if (authenticationToken.getPrincipal() instanceof OidcUser oidcUser) {
            return new ProviderIdentityProfile(
                    IdentityProvider.GOOGLE,
                    oidcUser.getAttribute("sub"),
                    oidcUser.getAttribute("email"),
                    oidcUser.getAttribute("name"),
                    oidcUser.getAttribute("picture"));

        }
        throw new OAuth2AuthenticationException(
                new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR),
                "Principal is not a OidcUser for registration: "
                        + authenticationToken.getAuthorizedClientRegistrationId());
    }

}
