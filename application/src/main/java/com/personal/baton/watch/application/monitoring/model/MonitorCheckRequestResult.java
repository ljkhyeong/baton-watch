package com.personal.baton.watch.application.monitoring.model;

import java.time.Instant;

/** 예약 접수 결과이며 실제 URL 점검 결과를 뜻하지 않는다. */
public record MonitorCheckRequestResult(Status status, Instant nextCheckAt, long retryAfterSeconds) {

    public enum Status {
        SCHEDULED,
        ALREADY_SCHEDULED,
        IN_PROGRESS,
        NOT_FOUND,
        INACTIVE,
        RATE_LIMITED
    }
}
