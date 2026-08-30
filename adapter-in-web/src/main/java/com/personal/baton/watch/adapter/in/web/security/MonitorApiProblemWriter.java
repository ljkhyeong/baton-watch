package com.personal.baton.watch.adapter.in.web.security;

import com.personal.baton.watch.adapter.in.web.MonitorApiProblem;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/** Spring MVC 바깥의 보안 경계에서 안정적인 문제 응답을 작성한다. */
final class MonitorApiProblemWriter {

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

        objectMapper.writeValue(
                response.getOutputStream(),
                MonitorApiProblem.of(typeSlug, title, code).toProblemDetail(status, includeInstance));
    }
}
