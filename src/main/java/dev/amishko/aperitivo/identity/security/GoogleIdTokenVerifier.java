package dev.amishko.aperitivo.identity.security;

import dev.amishko.aperitivo.identity.dto.IdentityProvider;
import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Slf4j
@RequiredArgsConstructor
@Component
public class GoogleIdTokenVerifier {

    private final ClientRegistrationRepository clientRegistrationRepository;

    public ProviderIdentityProfile verifyIdentity(String idToken) {
        ClientRegistration google = clientRegistrationRepository.findByRegistrationId("google");
        NimbusJwtDecoder googleJwtDecoder = NimbusJwtDecoder.withIssuerLocation(google.getProviderDetails().getIssuerUri()).build();
        googleJwtDecoder.setJwtValidator(new OidcIdTokenValidator(google));
        googleJwtDecoder.setJwtValidator(new JwtClaimValidator<>("email_verified", claim -> Boolean.TRUE));

        Jwt jwt = null;
        try {
            if (isNotBlank(idToken)) {
                jwt = googleJwtDecoder.decode(idToken);
            } else {
                log.error("Google Id token should be provided");
                throw new BadCredentialsException("Invalid idToken");
            }
        } catch (JwtValidationException ex) {
            log.error("Google Id token verification failed", ex);
        }
        log.info("Google Id token verification successful");
        return buildIdentityProfile(jwt);
    }

    private ProviderIdentityProfile buildIdentityProfile(Jwt jwt) {
        if (Objects.isNull(jwt)) {
            throw new BadCredentialsException("Google Id token verification failed");
        }
        return new ProviderIdentityProfile(
                IdentityProvider.GOOGLE,
                jwt.getClaimAsString("sub"),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("picture"));
    }


}
