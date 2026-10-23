package dev.amishko.aperitivo.identity.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class JwtTokenConverter implements Converter<Jwt, CurrentUserAuthenticationToken> {

    @Override
    public CurrentUserAuthenticationToken convert(Jwt source) {
        return new CurrentUserAuthenticationToken(source, Collections.emptySet());
    }
}
