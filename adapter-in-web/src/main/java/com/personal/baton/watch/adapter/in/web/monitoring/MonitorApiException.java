package com.personal.baton.watch.adapter.in.web.monitoring;

import com.personal.baton.watch.adapter.in.web.MonitorApiProblem;
import org.springframework.http.HttpStatus;

final class MonitorApiException extends RuntimeException {

    private final HttpStatus status;
    private final MonitorApiProblem problem;
    private final long retryAfterSeconds;

    private MonitorApiException(HttpStatus status, String slug, String title, String code) {
        this(status, slug, title, code, 0);
    }

    private MonitorApiException(HttpStatus status, String slug, String title, String code, long retryAfterSeconds) {
        super(title);
        this.status = status;
        this.problem = MonitorApiProblem.of(slug, title, code);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    static MonitorApiException invalidRequest() {
        return new MonitorApiException(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", "INVALID_REQUEST");
    }

    static MonitorApiException invalidTarget() {
        return new MonitorApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid-target-url",
                "Invalid target URL",
                "INVALID_TARGET_URL");
    }

    static MonitorApiException staleRevision() {
        return new MonitorApiException(
                HttpStatus.CONFLICT,
                "stale-source-revision",
                "Stale source revision",
                "STALE_SOURCE_REVISION");
    }

    static MonitorApiException revisionConflict() {
        return new MonitorApiException(
                HttpStatus.CONFLICT,
                "source-revision-conflict",
                "Source revision conflict",
                "SOURCE_REVISION_CONFLICT");
    }

    static MonitorApiException notFound() {
        return new MonitorApiException(HttpStatus.NOT_FOUND, "monitor-not-found", "Monitor not found", "MONITOR_NOT_FOUND");
    }

    static MonitorApiException inactive() {
        return new MonitorApiException(HttpStatus.CONFLICT, "monitor-inactive",
                "비활성 모니터는 재점검할 수 없습니다", "MONITOR_INACTIVE");
    }

    static MonitorApiException checkRequestRateLimited(long retryAfterSeconds) {
        return new MonitorApiException(HttpStatus.TOO_MANY_REQUESTS, "check-request-rate-limited",
                "재점검 요청 간격이 너무 짧습니다", "CHECK_REQUEST_RATE_LIMITED", retryAfterSeconds);
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    HttpStatus status() {
        return status;
    }

    MonitorApiProblem problem() {
        return problem;
    }
}
