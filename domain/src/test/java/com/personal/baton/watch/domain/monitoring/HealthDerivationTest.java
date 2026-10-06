package com.personal.baton.watch.domain.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HealthDerivationTest {

    @Test
    void successMakesHealthHealthyAndResetsFailures() {
        HealthDerivation result = new HealthDerivation(Health.BROKEN, 4).after(CheckOutcome.SUCCESS);

        assertEquals(new HealthDerivation(Health.HEALTHY, 0), result);
    }

    @Test
    void conclusiveFailuresBecomeDegradedThenBroken() {
        HealthDerivation first = new HealthDerivation(Health.UNKNOWN, 0).after(CheckOutcome.CONNECT_TIMEOUT);
        HealthDerivation second = first.after(CheckOutcome.HTTP_SERVER_ERROR);
        HealthDerivation third = second.after(CheckOutcome.DNS_FAILURE);

        assertEquals(new HealthDerivation(Health.DEGRADED, 1), first);
        assertEquals(new HealthDerivation(Health.DEGRADED, 2), second);
        assertEquals(new HealthDerivation(Health.BROKEN, 3), third);
    }

    @Test
    void internalFailureDoesNotChangeHealthAndStalenessMakesItUnknown() {
        HealthDerivation current = new HealthDerivation(Health.DEGRADED, 2);

        assertEquals(current, current.after(CheckOutcome.INTERNAL_FAILURE));
        assertEquals(new HealthDerivation(Health.UNKNOWN, 2), current.stale());
    }

    @ParameterizedTest
    @CsvSource({"UNKNOWN, -1", "HEALTHY, 1", "DEGRADED, 0", "DEGRADED, 3", "BROKEN, 2"})
    void rejectsHealthAndFailureCountMismatches(Health health, int consecutiveFailures) {
        assertThrows(IllegalArgumentException.class, () -> new HealthDerivation(health, consecutiveFailures));
    }
}
