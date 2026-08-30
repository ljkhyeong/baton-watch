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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @ParameterizedTest
    @CsvSource({
        "200, SUCCESS",
        "299, SUCCESS",
        "300, SUCCESS",
        "399, SUCCESS",
        "400, HTTP_CLIENT_ERROR",
        "499, HTTP_CLIENT_ERROR",
        "500, HTTP_SERVER_ERROR",
        "599, HTTP_SERVER_ERROR"
    })
    void mapsFinalHttpStatusBoundaries(int status, CheckOutcome expected) {
        assertEquals(
                expected, CheckObservation.forHttpStatus(status, Duration.ZERO, 0, 0).outcome());
    }

    @Test
    void boundsHttpObservationsToThePersistedTaxonomy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CheckObservation.forHttpStatus(199, Duration.ZERO, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> CheckObservation.forHttpStatus(600, Duration.ZERO, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CheckObservation(
                CheckOutcome.SUCCESS, null, Duration.ZERO, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CheckObservation(
                CheckOutcome.SUCCESS, 400, Duration.ZERO, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CheckObservation(
                CheckOutcome.DNS_FAILURE, 500, Duration.ZERO, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CheckObservation.failure(
                CheckOutcome.RESPONSE_TOO_LARGE, Duration.ofSeconds(1), -1, 0));
        assertThrows(IllegalArgumentException.class, () -> CheckObservation.failure(
                CheckOutcome.TOO_MANY_REDIRECTS, Duration.ofSeconds(1), 0, 4));
    }
}
