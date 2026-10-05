package com.personal.baton.watch.domain.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HealthDerivationPolicyTest {

    private final HealthDerivationPolicy policy = new HealthDerivationPolicy();

    @Test
    void successMakesHealthHealthyAndResetsFailures() {
        HealthDerivation result = policy.derive(new HealthDerivation(Health.BROKEN, 4), CheckOutcome.SUCCESS);

        assertEquals(new HealthDerivation(Health.HEALTHY, 0), result);
    }

    @Test
    void conclusiveFailuresBecomeDegradedThenBroken() {
        HealthDerivation first = policy.derive(
                new HealthDerivation(Health.UNKNOWN, 0), CheckOutcome.CONNECT_TIMEOUT);
        HealthDerivation second = policy.derive(first, CheckOutcome.HTTP_SERVER_ERROR);
        HealthDerivation third = policy.derive(second, CheckOutcome.DNS_FAILURE);

        assertEquals(new HealthDerivation(Health.DEGRADED, 1), first);
        assertEquals(new HealthDerivation(Health.DEGRADED, 2), second);
        assertEquals(new HealthDerivation(Health.BROKEN, 3), third);
    }

    @Test
    void internalFailureDoesNotChangeHealthAndStalenessMakesItUnknown() {
        HealthDerivation current = new HealthDerivation(Health.DEGRADED, 2);

        assertEquals(current, policy.derive(current, CheckOutcome.INTERNAL_FAILURE));
        assertEquals(new HealthDerivation(Health.UNKNOWN, 2), policy.markStale(current));
    }

    @ParameterizedTest
    @CsvSource({"UNKNOWN, -1", "HEALTHY, 1", "DEGRADED, 0", "DEGRADED, 3", "BROKEN, 2"})
    void rejectsHealthAndFailureCountMismatches(Health health, int consecutiveFailures) {
        assertThrows(IllegalArgumentException.class, () -> new HealthDerivation(health, consecutiveFailures));
    }
}
