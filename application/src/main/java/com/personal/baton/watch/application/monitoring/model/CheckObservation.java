package com.personal.baton.watch.application.monitoring.model;

import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import java.time.Duration;
import java.util.Objects;

public record CheckObservation(
        CheckOutcome outcome,
        Integer httpStatusCode,
        Duration duration,
        long responseBytes,
        int redirectCount) {

    private static final int MAX_REDIRECT_COUNT = 3;

    public CheckObservation {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(duration, "duration");
        validateStatus(outcome, httpStatusCode);
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must be non-negative");
        }
        if (responseBytes < 0) {
            throw new IllegalArgumentException("response bytes must be non-negative");
        }
        if (redirectCount < 0 || redirectCount > MAX_REDIRECT_COUNT) {
            throw new IllegalArgumentException("redirect count is outside the bounded policy");
        }
    }

    public static CheckObservation forHttpStatus(
            int httpStatusCode, Duration duration, long responseBytes, int redirectCount) {
        if (httpStatusCode >= 200 && httpStatusCode <= 399) {
            return new CheckObservation(CheckOutcome.SUCCESS, httpStatusCode, duration, responseBytes, redirectCount);
        }
        if (httpStatusCode >= 400 && httpStatusCode <= 499) {
            return new CheckObservation(
                    CheckOutcome.HTTP_CLIENT_ERROR, httpStatusCode, duration, responseBytes, redirectCount);
        }
        if (httpStatusCode >= 500 && httpStatusCode <= 599) {
            return new CheckObservation(
                    CheckOutcome.HTTP_SERVER_ERROR, httpStatusCode, duration, responseBytes, redirectCount);
        }
        throw new IllegalArgumentException("unsupported final HTTP status");
    }

    public static CheckObservation failure(
            CheckOutcome outcome, Duration duration, long responseBytes, int redirectCount) {
        if (!outcome.isTargetFailure()
                || outcome == CheckOutcome.HTTP_CLIENT_ERROR
                || outcome == CheckOutcome.HTTP_SERVER_ERROR) {
            throw new IllegalArgumentException("outcome requires different observation metadata");
        }
        return new CheckObservation(outcome, null, duration, responseBytes, redirectCount);
    }

    public static CheckObservation internalFailure() {
        return new CheckObservation(CheckOutcome.INTERNAL_FAILURE, null, Duration.ZERO, 0, 0);
    }

    private static void validateStatus(CheckOutcome outcome, Integer status) {
        boolean valid = switch (outcome) {
            case SUCCESS -> status != null && status >= 200 && status <= 399;
            case HTTP_CLIENT_ERROR -> status != null && status >= 400 && status <= 499;
            case HTTP_SERVER_ERROR -> status != null && status >= 500 && status <= 599;
            default -> status == null;
        };
        if (!valid) {
            throw new IllegalArgumentException("HTTP status does not match outcome");
        }
    }
}
