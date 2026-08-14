package com.personal.baton.watch.application.monitoring.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record EventDeliveryRetryPolicy(Duration initialDelay, Duration maxDelay) {

    public EventDeliveryRetryPolicy {
        initialDelay = TimeBoundaryPolicy.requireEventDeliveryRetryDelay(initialDelay, "initialDelay");
        maxDelay = TimeBoundaryPolicy.requireEventDeliveryRetryDelay(maxDelay, "maxDelay");
        if (initialDelay.compareTo(maxDelay) > 0) {
            throw new IllegalArgumentException("initialDelay must not exceed maxDelay");
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
        return TimeBoundaryPolicy.add(completedAt, delay, "retry delay");
    }
}
