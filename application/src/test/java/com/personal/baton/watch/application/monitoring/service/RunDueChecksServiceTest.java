package com.personal.baton.watch.application.monitoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunDueChecksServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration INTERVAL = Duration.ofSeconds(60);
    private static final Duration INTERNAL_RETRY = Duration.ofSeconds(30);
    private static final ClaimedCheck CLAIM = new ClaimedCheck(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            new TargetUrl("https://example.com/health"),
            NOW.minusSeconds(12),
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
        assertEquals(new DueCheckBatchResult(1, 1, 0, 0, Duration.ofSeconds(12)), result);
        assertEquals(LEASE, persistence.leaseDuration);
        assertEquals(1, persistence.limit);
        assertEquals(CheckOutcome.SUCCESS, persistence.finalization.observation().outcome());
        assertEquals(NOW.plus(INTERVAL), persistence.finalization.nextCheckAt());
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
                CLAIM.scheduledAt(),
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

    @Test
    void reportsTheMaximumScheduleDelayAcrossTheClaimedBatch() {
        RecordingWorkPersistence persistence = new RecordingWorkPersistence(new ArrayList<>());
        persistence.claims = List.of(
                claim(1, 3),
                claim(2, 17),
                claim(3, 5));
        RunDueChecksService service = new RunDueChecksService(
                persistence,
                target -> CheckObservation.forHttpStatus(204, Duration.ZERO, 0, 0),
                Clock.fixed(NOW, ZoneOffset.UTC),
                LEASE,
                INTERVAL,
                INTERNAL_RETRY,
                3);

        DueCheckBatchResult result = service.runDueChecks();

        assertEquals(new DueCheckBatchResult(3, 3, 0, 0, Duration.ofSeconds(17)), result);
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
                new DueCheckBatchResult(1, 0, alreadyFinalized, staleClaims, Duration.ofSeconds(12)),
                result);
    }

    private static ClaimedCheck claim(long sequence, long scheduleDelaySeconds) {
        return new ClaimedCheck(
                new UUID(0, sequence),
                new UUID(1, sequence),
                CLAIM.targetUrl(),
                NOW.minusSeconds(scheduleDelaySeconds),
                NOW,
                false);
    }

    private static final class RecordingWorkPersistence implements CheckWorkPersistencePort {

        private final List<String> calls;
        private Duration leaseDuration;
        private int limit;
        private CheckFinalization finalization;
        private List<ClaimedCheck> claims = List.of(CLAIM);
        private CheckFinalizationStatus status = CheckFinalizationStatus.APPLIED;

        private RecordingWorkPersistence(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public List<ClaimedCheck> claimDueChecks(Duration leaseDuration, int limit) {
            calls.add("claim");
            this.leaseDuration = leaseDuration;
            this.limit = limit;
            if (claims.isEmpty()) {
                return claims;
            }
            ClaimedCheck next = claims.getFirst();
            claims = claims.subList(1, claims.size());
            return List.of(next);
        }

        @Override
        public CheckFinalizationStatus finalizeCheck(CheckFinalization finalization) {
            calls.add("finalize");
            this.finalization = finalization;
            return status;
        }

        @Override
        public int purgeAttempts(Instant completedBefore, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
