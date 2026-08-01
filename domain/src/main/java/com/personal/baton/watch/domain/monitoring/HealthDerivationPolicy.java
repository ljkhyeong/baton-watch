package com.personal.baton.watch.domain.monitoring;

import java.util.Objects;

public final class HealthDerivationPolicy {

    public HealthDerivation derive(HealthDerivation current, CheckOutcome outcome) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(outcome, "outcome");

        if (!outcome.isConclusive()) {
            return current;
        }
        if (outcome.isSuccess()) {
            return new HealthDerivation(Health.HEALTHY, 0);
        }

        int failures = current.consecutiveFailures() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : current.consecutiveFailures() + 1;
        Health health = failures >= 3 ? Health.BROKEN : Health.DEGRADED;
        return new HealthDerivation(health, failures);
    }

    public HealthDerivation markStale(HealthDerivation current) {
        Objects.requireNonNull(current, "current");
        return new HealthDerivation(Health.UNKNOWN, current.consecutiveFailures());
    }
}
