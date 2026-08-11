package com.personal.baton.watch.application.monitoring.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record EventDeliveryRetryPolicy(Duration initialDelay, Duration maxDelay) {

    public static final Duration MAX_SUPPORTED_DELAY = Duration.ofDays(30);

    public EventDeliveryRetryPolicy {
        initialDelay = requirePositive(initialDelay, "initialDelay");
        maxDelay = requirePositive(maxDelay, "maxDelay");
        if (initialDelay.compareTo(maxDelay) > 0) {
            throw new IllegalArgumentException("initialDelay must not exceed maxDelay");
        }
        if (maxDelay.compareTo(MAX_SUPPORTED_DELAY) > 0) {
            throw new IllegalArgumentException(
                    "maxDelay must not exceed " + MAX_SUPPORTED_DELAY.toDays() + " days");
        }
    }

    public Instant nextAttemptAt(Instant completedAt, int deliveryAttempt) {
        Objects.requireNonNull(completedAt, "completedAt");
        if (deliveryAttempt <= 0) {
            throw new IllegalArgumentException("deliveryAttempt must be positive");
        }

        Duration delay = initialDelay;
        int remainingDoublings = deliveryAttempt - 1;
        while (remainingDoublings > 0 && delay.compareTo(maxDelay) < 0) {
            Duration doubled = delay.multipliedBy(2);
            delay = doubled.compareTo(maxDelay) > 0 ? maxDelay : doubled;
            remainingDoublings--;
        }
        return completedAt.plus(delay);
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
