package dev.amishko.aperitivo.identity.security.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

@Component
@RequiredArgsConstructor
public class ProblemDetailsAccessDeniedHandler implements AccessDeniedHandler {

    private static final String DOMAIN_TYPE = "https://aperitivo.app/errors/unauthorized";

    private final JsonMapper jsonMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException {
        int status = response.getStatus();

        ProblemDetail problemDetail = ProblemDetail.forStatus(status);

        problemDetail.setType(URI.create(DOMAIN_TYPE));
        problemDetail.setTitle("Authorization Failed");
        problemDetail.setProperty("Content-Type", "application/problem+json");
        problemDetail.setDetail(accessDeniedException.getMessage());

        jsonMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
