package dev.amishko.aperitivo.identity.exception.oauth;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.BadCredentialsException;

public class AperitivoRefreshTokenException extends BadCredentialsException {
    public AperitivoRefreshTokenException(@Nullable String msg) {
        super(msg);
    }
}
