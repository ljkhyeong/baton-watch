package com.personal.baton.watch.application.monitoring.model;

import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import java.time.Duration;
import java.util.Objects;

public record CheckObservation(
        CheckOutcome outcome,
        Integer httpStatusCode,
        Duration duration,
        int redirectCount,
        boolean responseReceived) {

    public static final int MAX_REDIRECT_COUNT = 3;

    public CheckObservation {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(duration, "duration");
        validateStatus(outcome, httpStatusCode);
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must be non-negative");
        }
        if (redirectCount < 0 || redirectCount > MAX_REDIRECT_COUNT) {
            throw new IllegalArgumentException("redirect count is outside the bounded policy");
        }
    }

    public CheckObservation(CheckOutcome outcome, Integer httpStatusCode, Duration duration, int redirectCount) {
        this(
                outcome,
                httpStatusCode,
                duration,
                redirectCount,
                Objects.requireNonNull(outcome, "outcome").receivedResponse());
    }

    public static CheckObservation forHttpStatus(
            int httpStatusCode, Duration duration, int redirectCount) {
        CheckOutcome outcome = httpOutcome(httpStatusCode);
        if (outcome == null) {
            throw new IllegalArgumentException("unsupported final HTTP status");
        }
        return new CheckObservation(outcome, httpStatusCode, duration, redirectCount);
    }

    /** 응답은 받았지만 저장 계약의 상태 범위(200~599) 밖이면 코드 없이 네트워크 실패로 남긴다. */
    public static CheckObservation forUnsupportedStatus(Duration duration, int redirectCount) {
        return new CheckObservation(CheckOutcome.NETWORK_FAILURE, null, duration, redirectCount, true);
    }

    public static CheckObservation failure(
            CheckOutcome outcome, Duration duration, int redirectCount) {
        return new CheckObservation(outcome, null, duration, redirectCount);
    }

    public static CheckObservation internalFailure() {
        return failure(CheckOutcome.INTERNAL_FAILURE, Duration.ZERO, 0);
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
