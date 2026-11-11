package dev.amishko.aperitivo.identity.util.web;

import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshToken;
import lombok.experimental.UtilityClass;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.time.Instant;

@UtilityClass
public class RefreshTokenCookieUtil {

    public static ResponseCookie setRefreshCookie(AperitivoRefreshToken aperitivoRefreshToken, Instant now) {
        return ResponseCookie.from("refresh_token", aperitivoRefreshToken.refreshToken())
                .sameSite("Strict")
                .httpOnly(true)
                .path("/api/auth")
                .secure(true)
                .maxAge(Duration.between(now, aperitivoRefreshToken.expiresAt()))
                .build();
    }
}
