package com.personal.baton.watch.adapter.in.web;

import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** MVC와 보안 필터가 공유하는 오류 유형과 응답 필드다. 민감정보는 포함하지 않는다. */
public record MonitorApiProblem(URI type, String title, String code) {

    public static final MonitorApiProblem INVALID_REQUEST =
            of("invalid-request", "요청 형식이 올바르지 않습니다", "INVALID_REQUEST");
    public static final MonitorApiProblem REQUEST_REJECTED =
            of("request-rejected", "허용되지 않는 HTTP 요청입니다", "REQUEST_REJECTED");
    private static final URI REDACTED_REQUEST = URI.create("urn:baton-watch:request");

    public static MonitorApiProblem of(String slug, String title, String code) {
        return new MonitorApiProblem(URI.create("urn:baton-watch:problem:" + slug), title, code);
    }

    public ProblemDetail toProblemDetail(HttpStatusCode status, boolean includeInstance) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(type);
        problem.setTitle(title);
        if (includeInstance) {
            problem.setInstance(REDACTED_REQUEST);
        }
        problem.setProperty("code", code);
        return problem;
    }
}
