package com.personal.baton.watch.application.monitoring.model;

import java.util.Objects;

public record EventDeliveryObservation(EventDeliveryOutcome outcome, Integer httpStatusCode) {

    public EventDeliveryObservation {
        Objects.requireNonNull(outcome, "outcome");
        validateHttpStatus(outcome, httpStatusCode);
    }

    public static EventDeliveryObservation delivered(int httpStatusCode) {
        if (httpStatusCode < 200 || httpStatusCode > 299) {
            throw new IllegalArgumentException("delivered HTTP status must be in the 2xx range");
        }
        return new EventDeliveryObservation(EventDeliveryOutcome.DELIVERED, httpStatusCode);
    }

    public static EventDeliveryObservation forHttpStatus(int httpStatusCode) {
        if (httpStatusCode >= 200 && httpStatusCode <= 299) {
            return delivered(httpStatusCode);
        }
        if (httpStatusCode >= 300 && httpStatusCode <= 499) {
            return new EventDeliveryObservation(EventDeliveryOutcome.HTTP_CLIENT_ERROR, httpStatusCode);
        }
        if (httpStatusCode >= 500 && httpStatusCode <= 599) {
            return new EventDeliveryObservation(EventDeliveryOutcome.HTTP_SERVER_ERROR, httpStatusCode);
        }
        throw new IllegalArgumentException("unsupported final HTTP status");
    }

    public static EventDeliveryObservation failure(EventDeliveryOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == EventDeliveryOutcome.DELIVERED
                || outcome == EventDeliveryOutcome.HTTP_CLIENT_ERROR
                || outcome == EventDeliveryOutcome.HTTP_SERVER_ERROR) {
            throw new IllegalArgumentException("outcome requires HTTP response metadata");
        }
        return new EventDeliveryObservation(outcome, null);
    }

    public static EventDeliveryObservation internalFailure() {
        return failure(EventDeliveryOutcome.INTERNAL_FAILURE);
    }

    private static void validateHttpStatus(EventDeliveryOutcome outcome, Integer httpStatusCode) {
        boolean valid = switch (outcome) {
            case DELIVERED -> httpStatusCode != null && httpStatusCode >= 200 && httpStatusCode <= 299;
            case HTTP_CLIENT_ERROR -> httpStatusCode != null && httpStatusCode >= 300 && httpStatusCode <= 499;
            case HTTP_SERVER_ERROR -> httpStatusCode != null && httpStatusCode >= 500 && httpStatusCode <= 599;
            default -> httpStatusCode == null;
        };
        if (!valid) {
            throw new IllegalArgumentException("HTTP status does not match delivery outcome");
        }
    }
}
