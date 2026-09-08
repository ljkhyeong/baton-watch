package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
        List<String> calls = new ArrayList<>();
        RecordingWorkPersistence persistence = new RecordingWorkPersistence(calls);
        RunDueChecksService service = new RunDueChecksService(
                persistence,
                target -> {
                    calls.add("check");
                    return CheckObservation.forHttpStatus(204, Duration.ZERO, 0, 0);
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                LEASE,
                INTERVAL,
                INTERNAL_RETRY,
                5);

        DueCheckBatchResult result = service.runDueChecks();

        assertEquals(List.of("claim", "check", "finalize", "claim"), calls);
        assertEquals(new DueCheckBatchResult(1, 1, 0, 0), result);
        assertEquals(LEASE, persistence.leaseDuration);
        assertEquals(CheckOutcome.SUCCESS, persistence.finalization.observation().outcome());
        assertEquals(NOW.plus(INTERVAL), persistence.finalization.nextCheckAt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesInterruptionAndStopsClaimingMoreChecks(boolean interruptedBeforeStart) {
        List<String> calls = new ArrayList<>();
        RecordingWorkPersistence persistence = new RecordingWorkPersistence(calls);
        RunDueChecksService service = new RunDueChecksService(
                persistence,
                target -> {
                    calls.add("check");
                    Thread.currentThread().interrupt();
                    return CheckObservation.internalFailure();
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                LEASE,
                INTERVAL,
                INTERNAL_RETRY,
                2);

        try {
            if (interruptedBeforeStart) {
                Thread.currentThread().interrupt();
            }

            DueCheckBatchResult result = service.runDueChecks();

            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(interruptedBeforeStart ? List.of() : List.of("claim", "check", "finalize"), calls);
            int completed = interruptedBeforeStart ? 0 : 1;
            assertEquals(new DueCheckBatchResult(completed, completed, 0, 0), result);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void convertsUnexpectedCheckerRuntimeErrorsToSafeInternalFailures() {
        RecordingWorkPersistence persistence = new RecordingWorkPersistence(new ArrayList<>());
        RunDueChecksService service = new RunDueChecksService(
                persistence,
                target -> {
                    throw new IllegalStateException("secret exception detail");
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                LEASE,
                INTERVAL,
                INTERNAL_RETRY,
                1);

        service.runDueChecks();

        assertEquals(CheckObservation.internalFailure(), persistence.finalization.observation());
        assertEquals(NOW.plus(INTERNAL_RETRY), persistence.finalization.nextCheckAt());
    }

    @Test
    void usesTheDatabaseClaimTimeAsTheCompletionFloor() {
        Instant databaseClaimedAt = NOW.plusSeconds(5);
        RecordingWorkPersistence persistence = new RecordingWorkPersistence(new ArrayList<>());
        persistence.claims = List.of(new ClaimedCheck(
                CLAIM.attemptId(),
                CLAIM.leaseToken(),
                CLAIM.targetUrl(),
                databaseClaimedAt,
                false));
        RunDueChecksService service = new RunDueChecksService(
                persistence,
                target -> CheckObservation.forHttpStatus(204, Duration.ZERO, 0, 0),
                Clock.fixed(NOW, ZoneOffset.UTC),
                LEASE,
                INTERVAL,
                INTERNAL_RETRY,
                1);

        service.runDueChecks();

        assertEquals(databaseClaimedAt, persistence.finalization.completedAt());
        assertEquals(databaseClaimedAt.plus(INTERVAL), persistence.finalization.nextCheckAt());
    }

    @ParameterizedTest
    @EnumSource(
            value = CheckFinalizationStatus.class,
            names = {"ALREADY_FINALIZED", "STALE_CLAIM"})
    void reportsNonAppliedFinalizationsByTheirPersistenceStatus(CheckFinalizationStatus status) {
        RecordingWorkPersistence persistence = new RecordingWorkPersistence(new ArrayList<>());
        persistence.status = status;
        RunDueChecksService service = new RunDueChecksService(
                persistence,
                target -> CheckObservation.forHttpStatus(204, Duration.ZERO, 0, 0),
                Clock.fixed(NOW, ZoneOffset.UTC),
                LEASE,
                INTERVAL,
                INTERNAL_RETRY,
                1);

        DueCheckBatchResult result = service.runDueChecks();

        int alreadyFinalized = status == CheckFinalizationStatus.ALREADY_FINALIZED ? 1 : 0;
        int staleClaims = status == CheckFinalizationStatus.STALE_CLAIM ? 1 : 0;
        assertEquals(
                new DueCheckBatchResult(1, 0, alreadyFinalized, staleClaims),
                result);
    }

    private static final class RecordingWorkPersistence implements CheckWorkPersistencePort {

        private final List<String> calls;
        private Duration leaseDuration;
        private CheckFinalization finalization;
        private List<ClaimedCheck> claims = List.of(CLAIM);
        private CheckFinalizationStatus status = CheckFinalizationStatus.APPLIED;

        private RecordingWorkPersistence(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Optional<ClaimedCheck> claimDueCheck(Duration leaseDuration) {
            calls.add("claim");
            this.leaseDuration = leaseDuration;
            if (claims.isEmpty()) {
                return Optional.empty();
            }
            ClaimedCheck next = claims.getFirst();
            claims = claims.subList(1, claims.size());
            return Optional.of(next);
        }

        @Override
        public CheckFinalizationStatus finalizeCheck(CheckFinalization finalization) {
            calls.add("finalize");
            this.finalization = finalization;
            return status;
        }

        @Override
        public Duration getOldestDueCheckDelay() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int purgeAttempts(Instant completedBefore, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
