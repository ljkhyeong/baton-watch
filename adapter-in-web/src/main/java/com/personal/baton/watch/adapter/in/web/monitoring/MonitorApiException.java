package com.personal.baton.watch.adapter.in.web.monitoring;

import com.personal.baton.watch.adapter.in.web.MonitorApiProblem;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** 상태·헤더는 ErrorResponseException 경로로 전달하고, 응답 필드는 MonitorApiProblem으로 정한다. */
final class MonitorApiException extends ErrorResponseException {

    private final MonitorApiProblem problem;

    private MonitorApiException(HttpStatus status, String slug, String title, String code) {
        super(status);
        this.problem = MonitorApiProblem.of(slug, title, code);
    }

    static MonitorApiException invalidTarget() {
        return new MonitorApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid-target-url",
                "점검할 수 없는 URL입니다",
                "INVALID_TARGET_URL");
    }

    static MonitorApiException staleRevision() {
        return new MonitorApiException(
                HttpStatus.CONFLICT,
                "stale-source-revision",
                "저장된 리비전보다 오래된 요청입니다",
                "STALE_SOURCE_REVISION");
    }

    static MonitorApiException revisionConflict() {
        return new MonitorApiException(
                HttpStatus.CONFLICT,
                "source-revision-conflict",
                "같은 리비전에 다른 내용이 등록되어 있습니다",
                "SOURCE_REVISION_CONFLICT");
    }

    static MonitorApiException notFound() {
        return new MonitorApiException(HttpStatus.NOT_FOUND, "monitor-not-found", "등록된 점검 대상이 없습니다", "MONITOR_NOT_FOUND");
    }

    static MonitorApiException inactive() {
        return new MonitorApiException(HttpStatus.CONFLICT, "monitor-inactive",
                "비활성 점검 대상은 재점검할 수 없습니다", "MONITOR_INACTIVE");
    }

    static MonitorApiException payloadTooLarge() {
        return new MonitorApiException(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large",
                "요청 본문이 허용 크기를 초과했습니다", "PAYLOAD_TOO_LARGE");
    }

    // 헤더가 인스턴스마다 가변이므로 예외를 캐시하지 않고 매번 만든다.
    static MonitorApiException checkRequestRateLimited(long retryAfterSeconds) {
        MonitorApiException exception = new MonitorApiException(HttpStatus.TOO_MANY_REQUESTS,
                "check-request-rate-limited", "재점검 요청 간격이 너무 짧습니다", "CHECK_REQUEST_RATE_LIMITED");
        exception.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return exception;
    }

    MonitorApiProblem problem() {
        return problem;
    }
}
