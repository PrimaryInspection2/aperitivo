package dev.amishko.aperitivo.identity.security;

import dev.amishko.aperitivo.identity.CurrentUser;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

@Component
public class AperitivoJwtTokenConverter implements Converter<Jwt, JwtAuthenticationToken> {

    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        UUID userUuid = extractUserUuid(jwt);
        CurrentUser currentUser = new CurrentUser(userUuid);

        return new JwtAuthenticationToken(jwt, currentUser, Collections.emptyList());

    }

    private UUID extractUserUuid(Jwt jwt) {
        return Optional.ofNullable(jwt)
                .map(Jwt::getSubject)
                .flatMap(AperitivoJwtTokenConverter::safeParseUserUuid)
                .orElseThrow(() -> new InvalidBearerTokenException("Missing or invalid 'sub' claim. UUID format expected"));
    }

    private static Optional<UUID> safeParseUserUuid(String uuid) {
        try {
            return Optional.of(UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
