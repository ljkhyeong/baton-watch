package com.personal.baton.watch.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import tools.jackson.databind.ObjectMapper;

/** Writes a stable redacted response when the HTTP firewall rejects a request. */
public final class MonitorApiRequestRejectedHandler implements RequestRejectedHandler {

    private static final URI REQUEST_REJECTED_TYPE =
            URI.create("urn:baton-watch:problem:request-rejected");
    private static final URI REDACTED_REQUEST = URI.create("urn:baton-watch:request");

    private final ObjectMapper objectMapper;

    public MonitorApiRequestRejectedHandler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            RequestRejectedException requestRejectedException) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        objectMapper.writeValue(response.getOutputStream(), requestRejectedProblem());
    }

    private static ProblemDetail requestRejectedProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(REQUEST_REJECTED_TYPE);
        problem.setTitle("Request rejected");
        problem.setInstance(REDACTED_REQUEST);
        problem.setProperty("code", "REQUEST_REJECTED");
        return problem;
    }
}
