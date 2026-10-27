package dev.amishko.aperitivo.identity.security;

import dev.amishko.aperitivo.identity.dto.IdentityProvider;
import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Slf4j
@Component
public class GoogleIdTokenVerifier {

    private final NimbusJwtDecoder googleJwtDecoder;
    private final ClientRegistration googleClientRegistration;

    public GoogleIdTokenVerifier(ClientRegistrationRepository clientRegistrationRepository) {
        this.googleClientRegistration = clientRegistrationRepository.findByRegistrationId("google");
        this.googleJwtDecoder = NimbusJwtDecoder.withIssuerLocation(googleClientRegistration.getProviderDetails().getIssuerUri()).build();
        this.googleJwtDecoder.setJwtValidator(JwtValidators.createDefaultWithValidators(List.of(
                new OidcIdTokenValidator(googleClientRegistration),
                new JwtClaimValidator<>("email_verified", claim -> claim.equals("true")))));
    }

    public ProviderIdentityProfile verifyIdentity(String idToken) {
        Jwt jwt;
        try {
            if (isNotBlank(idToken)) {
                jwt = googleJwtDecoder.decode(idToken);
            } else {
                log.warn("Google Id token should be provided");
                throw new BadCredentialsException("Invalid idToken");
            }
        } catch (JwtException ex) {
            log.warn("Google Id token verification failed", ex);
            throw new BadCredentialsException("Invalid idToken");
        }
        log.warn("Google Id token verification successful");
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
