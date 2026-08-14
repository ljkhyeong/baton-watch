package com.personal.baton.watch.adapter.in.web.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

/** Spring MVC 바깥의 보안 경계에서 안정적인 문제 응답을 작성한다. */
final class MonitorApiProblemWriter {

    private static final URI REDACTED_REQUEST = URI.create("urn:baton-watch:request");

    private MonitorApiProblemWriter() {
    }

    static void write(
            ObjectMapper objectMapper,
            HttpServletResponse response,
            HttpStatus status,
            String typeSlug,
            String title,
            String code,
            boolean includeInstance) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);

        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("urn:baton-watch:problem:" + typeSlug));
        problem.setTitle(title);
        if (includeInstance) {
            problem.setInstance(REDACTED_REQUEST);
        }
        problem.setProperty("code", code);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
