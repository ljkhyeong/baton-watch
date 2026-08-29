package com.personal.baton.watch.domain.monitoring;

import java.util.Objects;

public record HealthDerivation(Health health, int consecutiveFailures) {

    public HealthDerivation {
        Objects.requireNonNull(health, "health");
        if (consecutiveFailures < 0) {
            throw new IllegalArgumentException("consecutive failures must be non-negative");
        }
        if (health == Health.HEALTHY && consecutiveFailures != 0) {
            throw new IllegalArgumentException("healthy state cannot have consecutive failures");
        }
        if (health == Health.DEGRADED && (consecutiveFailures == 0 || consecutiveFailures >= 3)) {
            throw new IllegalArgumentException("degraded state requires one or two consecutive failures");
        }
        if (health == Health.BROKEN && consecutiveFailures < 3) {
            throw new IllegalArgumentException("broken state requires at least three consecutive failures");
        }
    }
}
