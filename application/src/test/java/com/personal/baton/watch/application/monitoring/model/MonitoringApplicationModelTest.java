package com.personal.baton.watch.application.monitoring.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MonitoringApplicationModelTest {

    private static final ResourceReference REFERENCE = new ResourceReference("resource-1");
    private static final SourceRevision REVISION = new SourceRevision(7);
    private static final TargetUrl TARGET = new TargetUrl("https://example.com/health");

    @Test
    void synchronizationCommandKeepsStateAndTargetConsistent() {
        assertThrows(IllegalArgumentException.class, () -> new SynchronizeMonitorCommand(
                REFERENCE, REVISION, MonitoringState.ACTIVE, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new SynchronizeMonitorCommand(
                REFERENCE, REVISION, MonitoringState.INACTIVE, Optional.of(TARGET)));
    }

    @Test
    void synchronizationRejectsTargetsThatAreUnsafeForOutboundChecks() {
        TargetUrl historicalTarget = new TargetUrl("https://example.com/%0d%0aHost:internal");

        assertThrows(
                IllegalArgumentException.class,
                () -> SynchronizeMonitorCommand.active(REFERENCE, REVISION, historicalTarget));
    }

    @Test
    void boundsHttpObservationsToThePersistedTaxonomy() {
        assertEquals(CheckOutcome.SUCCESS, CheckObservation.forHttpStatus(204).outcome());
        assertEquals(CheckOutcome.HTTP_CLIENT_ERROR, CheckObservation.forHttpStatus(404).outcome());
        assertEquals(CheckOutcome.HTTP_SERVER_ERROR, CheckObservation.forHttpStatus(503).outcome());
        assertThrows(IllegalArgumentException.class, () -> CheckObservation.forHttpStatus(199));
        assertThrows(IllegalArgumentException.class, () -> new CheckObservation(
                CheckOutcome.SUCCESS, null, Duration.ZERO, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CheckObservation(
                CheckOutcome.DNS_FAILURE, 500, Duration.ZERO, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CheckObservation.failure(
                CheckOutcome.RESPONSE_TOO_LARGE, Duration.ofSeconds(1), -1, 0));
        assertThrows(IllegalArgumentException.class, () -> CheckObservation.failure(
                CheckOutcome.TOO_MANY_REDIRECTS, Duration.ofSeconds(1), 0, 4));
    }
}
