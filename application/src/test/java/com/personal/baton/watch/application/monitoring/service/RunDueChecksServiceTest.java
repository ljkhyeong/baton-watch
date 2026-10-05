package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.port.out.UrlChecker;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RunDueChecksServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration INTERVAL = Duration.ofSeconds(60);
    private static final Duration INTERNAL_RETRY = Duration.ofSeconds(30);
    private static final ClaimedCheck CLAIM = new ClaimedCheck(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            new TargetUrl("https://example.com/health"),
            NOW,
            false);

    @Test
    void claimsThenChecksThenFinalizesOutsideTheClaimOperation() {
        RecordingCheckWorkPersistence persistence = new RecordingCheckWorkPersistence(CLAIM);
        RunDueChecksService service = service(
                persistence,
                target -> {
                    persistence.calls.add("check");
                    return CheckObservation.forHttpStatus(204, Duration.ZERO, 0);
                },
                5);

        DueCheckBatchResult result = service.runDueChecks();

        assertEquals(List.of("claim", "check", "finalize", "claim"), persistence.calls);
        assertEquals(new DueCheckBatchResult(1, 1, 0, 0), result);
        assertEquals(LEASE, persistence.leaseDuration);
        assertEquals(CheckOutcome.SUCCESS, persistence.finalization.observation().outcome());
        assertEquals(NOW.plus(INTERVAL), persistence.finalization.nextCheckAt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesInterruptionAndStopsClaimingMoreChecks(boolean interruptedBeforeStart) {
        RecordingCheckWorkPersistence persistence = new RecordingCheckWorkPersistence(CLAIM);
        RunDueChecksService service = service(
                persistence,
                target -> {
                    persistence.calls.add("check");
                    Thread.currentThread().interrupt();
                    return CheckObservation.internalFailure();
                },
                2);

        try {
            if (interruptedBeforeStart) {
                Thread.currentThread().interrupt();
            }

            DueCheckBatchResult result = service.runDueChecks();

            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(interruptedBeforeStart ? List.of() : List.of("claim", "check", "finalize"), persistence.calls);
            int completed = interruptedBeforeStart ? 0 : 1;
            assertEquals(new DueCheckBatchResult(completed, completed, 0, 0), result);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void convertsUnexpectedCheckerRuntimeErrorsToSafeInternalFailures() {
        RecordingCheckWorkPersistence persistence = new RecordingCheckWorkPersistence(CLAIM);
        RunDueChecksService service = service(
                persistence,
                target -> {
                    throw new IllegalStateException("secret exception detail");
                },
                1);

        service.runDueChecks();

        assertEquals(CheckObservation.internalFailure(), persistence.finalization.observation());
        assertEquals(NOW.plus(INTERNAL_RETRY), persistence.finalization.nextCheckAt());
    }

    @Test
    void usesTheDatabaseClaimTimeAsTheCompletionFloor() {
        Instant databaseClaimedAt = NOW.plusSeconds(5);
        RecordingCheckWorkPersistence persistence = new RecordingCheckWorkPersistence(new ClaimedCheck(
                CLAIM.attemptId(),
                CLAIM.leaseToken(),
                CLAIM.targetUrl(),
                databaseClaimedAt,
                false));
        RunDueChecksService service = service(persistence, target -> CheckObservation.forHttpStatus(204, Duration.ZERO, 0), 1);

        service.runDueChecks();

        assertEquals(databaseClaimedAt, persistence.finalization.completedAt());
        assertEquals(databaseClaimedAt.plus(INTERVAL), persistence.finalization.nextCheckAt());
    }

    @ParameterizedTest
    @EnumSource(
            value = CheckFinalizationStatus.class,
            names = {"ALREADY_FINALIZED", "STALE_CLAIM"})
    void reportsNonAppliedFinalizationsByTheirPersistenceStatus(CheckFinalizationStatus status) {
        RecordingCheckWorkPersistence persistence = new RecordingCheckWorkPersistence(CLAIM);
        persistence.finalizationStatus = status;
        RunDueChecksService service = service(persistence, target -> CheckObservation.forHttpStatus(204, Duration.ZERO, 0), 1);

        DueCheckBatchResult result = service.runDueChecks();

        int alreadyFinalized = status == CheckFinalizationStatus.ALREADY_FINALIZED ? 1 : 0;
        int staleClaims = status == CheckFinalizationStatus.STALE_CLAIM ? 1 : 0;
        assertEquals(
                new DueCheckBatchResult(1, 0, alreadyFinalized, staleClaims),
                result);
    }

    private static RunDueChecksService service(
            RecordingCheckWorkPersistence persistence, UrlChecker checker, int batchSize) {
        return new RunDueChecksService(
                persistence, checker, Clock.fixed(NOW, ZoneOffset.UTC), LEASE, INTERVAL, INTERNAL_RETRY, batchSize);
    }
}
