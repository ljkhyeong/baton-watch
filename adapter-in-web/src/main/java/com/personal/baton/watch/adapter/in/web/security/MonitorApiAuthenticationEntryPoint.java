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
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/** Writes the stable RFC 9457-compatible unauthorized response for monitor APIs. */
public final class MonitorApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final UnauthorizedProblem UNAUTHORIZED = new UnauthorizedProblem(
            URI.create("urn:baton-watch:problem:unauthorized"),
            "Unauthorized",
            HttpStatus.UNAUTHORIZED.value(),
            "UNAUTHORIZED");

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
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), UNAUTHORIZED);
    }

    private record UnauthorizedProblem(URI type, String title, int status, String code) {
    }
}
