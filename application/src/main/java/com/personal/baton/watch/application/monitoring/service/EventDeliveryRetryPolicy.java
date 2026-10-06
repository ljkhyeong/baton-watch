package com.personal.baton.watch.application.monitoring.service;

import java.time.Duration;
import java.time.Instant;

public record EventDeliveryRetryPolicy(Duration initialDelay, Duration maxDelay) {

    /** 설정에 없는 상한으로, 지수 백오프의 기간·시각 계산 범위를 보장한다. */
    static final Duration MAX_DELAY = Duration.ofDays(30);

    public EventDeliveryRetryPolicy {
        if (!initialDelay.isPositive()
                || initialDelay.compareTo(maxDelay) > 0
                || maxDelay.compareTo(MAX_DELAY) > 0) {
            throw new IllegalArgumentException("retry delays must satisfy 0 < initialDelay <= maxDelay <= 30 days");
        }
    }

    Instant nextAttemptAt(Instant completedAt, int deliveryAttempt, Instant retryNotBefore) {
        Duration delay = initialDelay;
        int remainingDoublings = deliveryAttempt - 1;
        while (remainingDoublings > 0 && delay.compareTo(maxDelay) < 0) {
            Duration doubled = delay.multipliedBy(2);
            delay = doubled.compareTo(maxDelay) > 0 ? maxDelay : doubled;
            remainingDoublings--;
        }
        Instant nextAttemptAt = completedAt.plus(delay);
        if (retryNotBefore == null || !retryNotBefore.isAfter(nextAttemptAt)) {
            return nextAttemptAt;
        }
        Instant latestAttemptAt = completedAt.plus(maxDelay);
        return retryNotBefore.isAfter(latestAttemptAt) ? latestAttemptAt : retryNotBefore;
    }
}
