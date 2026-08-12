package dev.amishko.aperitivo.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
@RequiredArgsConstructor
public class OAuth2GoogleLoginFailureHandler implements AuthenticationFailureHandler {

    private static final String FALLBACK_ERROR_CODE = "authentication_failed";

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String errorCode = errorCode(exception);
        log.warn("OAuth2 login failed [{}]: {}", errorCode, exception.getMessage());
        response.sendRedirect("/login?error=" + URLEncoder.encode(errorCode, StandardCharsets.UTF_8));
    }

    private String errorCode(AuthenticationException exception) {
        return exception instanceof OAuth2AuthenticationException oAuth2Exception
                ? oAuth2Exception.getError().getErrorCode()
                : FALLBACK_ERROR_CODE;
    }
}
