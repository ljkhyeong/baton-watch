package com.personal.baton.watch.application.monitoring.model;

import java.time.Instant;
import java.util.Objects;

public record EventDeliveryObservation(
        EventDeliveryOutcome outcome, Integer httpStatusCode, Instant retryNotBefore) {

    public EventDeliveryObservation {
        Objects.requireNonNull(outcome, "outcome");
        validateHttpStatus(outcome, httpStatusCode);
        if (retryNotBefore != null && !Objects.equals(httpStatusCode, 429) && !Objects.equals(httpStatusCode, 503)) {
            throw new IllegalArgumentException("재시도 시각은 HTTP 429·503 응답에만 지정할 수 있습니다");
        }
    }

    public static EventDeliveryObservation forHttpStatus(int httpStatusCode) {
        return forHttpStatus(httpStatusCode, null);
    }

    public static EventDeliveryObservation forHttpStatus(int httpStatusCode, Instant retryNotBefore) {
        EventDeliveryOutcome outcome = httpOutcome(httpStatusCode);
        if (outcome == null) {
            throw new IllegalArgumentException("unsupported final HTTP status");
        }
        return new EventDeliveryObservation(outcome, httpStatusCode, retryNotBefore);
    }

    public static EventDeliveryObservation failure(EventDeliveryOutcome outcome) {
        return new EventDeliveryObservation(outcome, null, null);
    }

    public static EventDeliveryObservation internalFailure() {
        return failure(EventDeliveryOutcome.INTERNAL_FAILURE);
    }

    private static void validateHttpStatus(EventDeliveryOutcome outcome, Integer httpStatusCode) {
        boolean valid = switch (outcome) {
            case DELIVERED, HTTP_CLIENT_ERROR, HTTP_SERVER_ERROR ->
                    httpStatusCode != null && httpOutcome(httpStatusCode) == outcome;
            default -> httpStatusCode == null;
        };
        if (!valid) {
            throw new IllegalArgumentException("HTTP status does not match delivery outcome");
        }
    }

    private static EventDeliveryOutcome httpOutcome(int status) {
        return switch (status / 100) {
            case 2 -> EventDeliveryOutcome.DELIVERED;
            case 3, 4 -> EventDeliveryOutcome.HTTP_CLIENT_ERROR;
            case 5 -> EventDeliveryOutcome.HTTP_SERVER_ERROR;
            default -> null;
        };
    }
}
