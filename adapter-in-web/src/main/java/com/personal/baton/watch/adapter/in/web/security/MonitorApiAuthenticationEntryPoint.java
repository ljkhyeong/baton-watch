package com.personal.baton.watch.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/** Writes the stable RFC 9457-compatible unauthorized response for monitor APIs. */
public final class MonitorApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final URI UNAUTHORIZED_TYPE =
            URI.create("urn:baton-watch:problem:unauthorized");

    private final ObjectMapper objectMapper;

    public MonitorApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        objectMapper.writeValue(response.getOutputStream(), unauthorizedProblem());
    }

    private static ProblemDetail unauthorizedProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(UNAUTHORIZED_TYPE);
        problem.setTitle("Unauthorized");
        problem.setProperty("code", "UNAUTHORIZED");
        return problem;
    }
}
