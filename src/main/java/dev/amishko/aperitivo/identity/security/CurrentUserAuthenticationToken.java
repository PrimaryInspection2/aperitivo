package dev.amishko.aperitivo.identity.security;

import dev.amishko.aperitivo.identity.CurrentUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public class CurrentUserAuthenticationToken extends JwtAuthenticationToken {

    private final CurrentUser currentUser;

    public CurrentUserAuthenticationToken(Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
        super(jwt, authorities);
        currentUser = new CurrentUser(extractUserUuid(jwt));
    }

    private UUID extractUserUuid(Jwt jwt) {
        return Optional.ofNullable(jwt)
                .map(Jwt::getSubject)
                .map(UUID::fromString)
                .orElseThrow(() -> new InvalidBearerTokenException("Missing or invalid 'sub' claim. UUID format expected"));
    }

    @Override
    public Object getPrincipal() {
        return currentUser;
    }
}
