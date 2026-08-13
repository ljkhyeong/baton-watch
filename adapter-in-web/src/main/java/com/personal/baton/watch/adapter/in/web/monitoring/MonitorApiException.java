package com.personal.baton.watch.adapter.in.web.monitoring;

import java.net.URI;
import org.springframework.http.HttpStatus;

final class MonitorApiException extends RuntimeException {

    private final HttpStatus status;
    private final URI type;
    private final String title;
    private final String code;

    private MonitorApiException(HttpStatus status, String slug, String title, String code) {
        super(title);
        this.status = status;
        this.type = URI.create("urn:baton-watch:problem:" + slug);
        this.title = title;
        this.code = code;
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

    HttpStatus status() {
        return status;
    }

    URI type() {
        return type;
    }

    String title() {
        return title;
    }

    String code() {
        return code;
    }
}
