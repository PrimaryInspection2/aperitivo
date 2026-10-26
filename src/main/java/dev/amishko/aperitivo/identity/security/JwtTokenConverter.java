package dev.amishko.aperitivo.identity.security;

import dev.amishko.aperitivo.identity.CurrentUser;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtTokenConverter implements Converter<Jwt, CurrentUserAuthenticationToken> {

    @Override
    public CurrentUserAuthenticationToken convert(Jwt jwt) {
        UUID userUuid = extractUserUuid(jwt);
        CurrentUser currentUser = new CurrentUser(userUuid);
        return new CurrentUserAuthenticationToken(jwt, currentUser, Collections.emptySet());
    }

    private UUID extractUserUuid(Jwt jwt) {
        return Optional.ofNullable(jwt)
                .map(Jwt::getSubject)
                .map(UUID::fromString)
                .orElseThrow(() -> new InvalidBearerTokenException("Missing or invalid 'sub' claim. UUID format expected"));
    }
}
