package dev.amishko.aperitivo.identity.security;

import dev.amishko.aperitivo.identity.CurrentUser;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class JwtTokenConverter extends JwtAuthenticationToken implements Converter<Jwt, CurrentUser> {


    public JwtTokenConverter(Jwt jwt) {
        super(jwt);
    }

    private UUID extractUserUuid(Jwt jwt) {
        return Optional.ofNullable(jwt)
                .map(Jwt::getSubject)
                .map(UUID::fromString)
                .orElseThrow(() -> new JwtException("Missing or invalid 'sub' claim. UUID format expected"));
    }

    @Override
    public CurrentUser convert(Jwt source) {
        return new CurrentUser(extractUserUuid(source));
    }
}
