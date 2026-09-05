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
        CheckOutcome outcome = httpOutcome(httpStatusCode);
        if (outcome == null) {
            throw new IllegalArgumentException("unsupported final HTTP status");
        }
        return new CheckObservation(outcome, httpStatusCode, duration, responseBytes, redirectCount);
    }

    public static CheckObservation failure(
            CheckOutcome outcome, Duration duration, long responseBytes, int redirectCount) {
        if (!outcome.isConclusive()) {
            throw new IllegalArgumentException("outcome requires different observation metadata");
        }
        return new CheckObservation(outcome, null, duration, responseBytes, redirectCount);
    }

    public static CheckObservation internalFailure() {
        return new CheckObservation(CheckOutcome.INTERNAL_FAILURE, null, Duration.ZERO, 0, 0);
    }

    private static void validateStatus(CheckOutcome outcome, Integer status) {
        boolean valid = switch (outcome) {
            case SUCCESS, HTTP_CLIENT_ERROR, HTTP_SERVER_ERROR ->
                    status != null && httpOutcome(status) == outcome;
            default -> status == null;
        };
        if (!valid) {
            throw new IllegalArgumentException("HTTP status does not match outcome");
        }
    }

    private static CheckOutcome httpOutcome(int status) {
        return switch (status / 100) {
            case 2, 3 -> CheckOutcome.SUCCESS;
            case 4 -> CheckOutcome.HTTP_CLIENT_ERROR;
            case 5 -> CheckOutcome.HTTP_SERVER_ERROR;
            default -> null;
        };
    }
}
