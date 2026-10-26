package dev.amishko.aperitivo.identity.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;

public class CurrentUserAuthenticationToken extends JwtAuthenticationToken {


    public CurrentUserAuthenticationToken(Jwt jwt, Object principal, Collection<? extends GrantedAuthority> authorities) {
        super(jwt, principal, authorities);
    }

}
