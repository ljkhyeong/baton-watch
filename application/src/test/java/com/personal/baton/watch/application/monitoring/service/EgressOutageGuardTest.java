package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EgressOutageGuardTest {

    private static final CheckObservation DNS_FAILURE =
            CheckObservation.failure(CheckOutcome.DNS_FAILURE, Duration.ofSeconds(5), 0);

    private final EgressOutageGuard guard = new EgressOutageGuard();

    @Test
    void suspectsAnOutageFromTheFifthSilentHostUntilAnyTargetResponds() {
        failSilentHosts(4);

        assertTrue(guard.suspectsOutage(target("host-5.example"), DNS_FAILURE));
        assertTrue(guard.suspectsOutage(target("host-1.example"), DNS_FAILURE));
        assertFalse(guard.suspectsOutage(
                target("host-6.example"), CheckObservation.forHttpStatus(404, Duration.ZERO, 0)));
        assertFalse(guard.suspectsOutage(target("host-7.example"), DNS_FAILURE));
    }

    @Test
    void doesNotCountTheSameHostTwiceOrInternalFailures() {
        for (int attempt = 0; attempt < 10; attempt++) {
            assertFalse(guard.suspectsOutage(target("HOST-1.example"), DNS_FAILURE));
            assertFalse(guard.suspectsOutage(target("host-1.example"), DNS_FAILURE));
            assertFalse(guard.suspectsOutage(target("host-" + attempt + ".internal"), CheckObservation.internalFailure()));
        }
        failSilentHosts(4);

        assertTrue(guard.suspectsOutage(target("host-5.example"), DNS_FAILURE));
    }

    @Test
    void treatsAFailureAfterARedirectResponseAsWorkingEgress() {
        failSilentHosts(4);

        assertFalse(guard.suspectsOutage(
                target("host-5.example"), CheckObservation.failure(CheckOutcome.CONNECT_TIMEOUT, Duration.ZERO, 1)));
        failSilentHosts(4);
        assertTrue(guard.suspectsOutage(target("host-5.example"), DNS_FAILURE));
    }

    @Test
    void treatsAnUnsupportedStatusResponseAsWorkingEgress() {
        failSilentHosts(4);

        assertFalse(guard.suspectsOutage(
                target("host-5.example"), CheckObservation.forUnsupportedStatus(Duration.ZERO, 0)));
        assertFalse(guard.suspectsOutage(target("host-6.example"), DNS_FAILURE));
    }

    private void failSilentHosts(int count) {
        for (int host = 1; host <= count; host++) {
            assertFalse(guard.suspectsOutage(target("host-" + host + ".example"), DNS_FAILURE));
        }
    }

    private static TargetUrl target(String host) {
        return new TargetUrl("https://" + host + "/resource");
    }
}
