package dev.amishko.aperitivo.identity.web.util;

import lombok.experimental.UtilityClass;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

@UtilityClass
public class RefreshCookieUtil {

    public final static String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    private final static String AUTH_API_BASE_PATH = "/api/auth";

    public static ResponseCookie buildRefreshCookie(String refreshToken, Duration maxAge) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, refreshToken)
                .sameSite("Strict")
                .httpOnly(true)
                .path(AUTH_API_BASE_PATH)
                .secure(true)
                .maxAge(maxAge)
                .build();
    }

}
