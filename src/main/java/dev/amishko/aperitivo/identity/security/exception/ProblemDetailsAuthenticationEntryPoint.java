package dev.amishko.aperitivo.identity.security.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

@Component
@RequiredArgsConstructor
public class ProblemDetailsAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String DOMAIN_TYPE = "https://aperitivo.app/errors/unauthentichated";

    private final JsonMapper jsonMapper;

    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        delegate.commence(request, response, authException);

        int status = response.getStatus();
        ProblemDetail problemDetail = ProblemDetail.forStatus(status);
        problemDetail.setType(URI.create(DOMAIN_TYPE));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setTitle("Authentication Failed");

        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        jsonMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
