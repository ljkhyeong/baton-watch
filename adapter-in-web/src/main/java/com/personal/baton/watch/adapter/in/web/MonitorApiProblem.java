package com.personal.baton.watch.adapter.in.web;

import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** MVC와 보안 필터가 공유하는 문제 유형과 비식별 응답 필드다. */
public record MonitorApiProblem(URI type, String title, String code) {

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
